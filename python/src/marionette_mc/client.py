"""One-session asyncio client; no command replay or gameplay reconnect policy."""
import asyncio
from collections import deque
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass
import json
import math
from typing import AsyncIterator, Iterable, Unpack, cast

from websockets.asyncio.client import ClientConnection, connect as ws_connect
from websockets.exceptions import ConnectionClosed

from .messages import (
    PROTOCOL_VERSION, Command, Configure, Error, Event, Hello, Input, InputFields,
    InventoryRequest, InventoryResult, InvalidMessage, Look, LookFields, MenuRef,
    Observation, Operation, Reliable, Role, Section, SlotRef, UnsupportedMessage,
    decode, validate,
)


class ClientError(Exception):
    """Base class for local client failures."""


class CapabilityError(ClientError):
    pass


class VersionError(ClientError):
    pass


class RoleError(ClientError):
    pass


class CapacityError(ClientError):
    pass


class ServerError(ClientError):
    def __init__(self, error: Error):
        self.error = error
        reason = error.get("reason")
        super().__init__(f"{error['code']}{f' ({reason})' if reason else ''}: {error['message']}")

    @property
    def code(self) -> str:
        return self.error["code"]

    @property
    def reason(self) -> str | None:
        """Machine-readable inventory rejection reason (``inventoryStorage``), if sent."""
        return self.error.get("reason")


@dataclass(frozen=True)
class Disconnect:
    """Local connection outcome, not a protocol event or action acknowledgment."""
    code: int | None
    reason: str
    cause: BaseException | None = None


class Disconnected(ClientError):
    def __init__(self, outcome: Disconnect):
        self.outcome = outcome
        super().__init__(f"disconnected ({outcome.code}): {outcome.reason}; pending outcomes unknown")


class RequestTimeout(TimeoutError):
    def __init__(self, request_id: str):
        self.request_id = request_id
        super().__init__(f"request {request_id} timed out; outcome unknown, inspect late replies; do not replay")


SECTION_CAPABILITIES = {"inventory": "inventoryState", "target": "targetState", "world": "worldState"}


def section_capabilities(sections: Iterable[str]) -> list[str]:
    """Selecting any mask needs ``playerState``; other sections also need their own capability."""
    selected = set(sections)
    return ["playerState"] + [cap for name, cap in SECTION_CAPABILITIES.items() if name in selected]


def positive(value: float, name: str) -> None:
    if isinstance(value, bool) or not math.isfinite(value) or value <= 0:
        raise ValueError(f"{name} must be positive and finite")


class Client:
    """Use ``async with connect(...)``. Stream consumers compete, not broadcast.

    Observations retain the latest frame. Errors and unmatched/late inventory
    replies share a bounded reliable queue. Subscribed events have their own
    bounded queue and never resolve requests. Overflow terminates the session
    explicitly instead of silently dropping replies or blocking the reader.
    Message types this client does not know are counted and ignored.
    """

    def __init__(self, socket: ClientConnection, hello: Hello, role: Role,
                 reliable_limit: int, pending_limit: int, request_timeout: float,
                 event_limit: int = 1024):
        self._socket = socket
        self.hello = hello
        self.role = role
        self._reliable_limit = reliable_limit
        self._pending_limit = pending_limit
        self._request_timeout = request_timeout
        self._pending: dict[str, asyncio.Future[Reliable | Disconnect]] = {}
        self._reliable: deque[Reliable] = deque()
        self._event_limit = event_limit
        self._events: deque[Event] = deque()
        self.last_event_seq = 0
        self.unknown_messages = 0
        self._observation: Observation | None = None
        self._changed = asyncio.Event()
        self._closed = asyncio.Event()
        self._outcome: Disconnect | None = None
        self._sequence = 0
        self.observations_coalesced = 0
        self._reader = asyncio.create_task(self._read(), name="marionette-reader")

    def require(self, *capabilities: str) -> None:
        missing = [name for name in capabilities if not self.hello["capabilities"].get(name)]
        if missing:
            raise CapabilityError(f"missing capabilities: {', '.join(missing)}")

    def _active(self) -> None:
        if self._outcome is not None:
            raise Disconnected(self._outcome)

    def _finish(self, outcome: Disconnect) -> None:
        if self._outcome is None:
            self._outcome = outcome
            for future in self._pending.values():
                if not future.done():
                    future.set_result(outcome)
            self._pending.clear()
            self._changed.set()
            self._closed.set()

    async def _read(self) -> None:
        cause: BaseException | None = None
        try:
            async for raw in self._socket:
                try:
                    message = decode(raw)
                except UnsupportedMessage:
                    self.unknown_messages += 1
                    continue
                if message["type"] == "event":
                    # A separate stream: an event never satisfies a pending request.
                    event = cast(Event, message)
                    if event["seq"] != self.last_event_seq + 1:
                        raise InvalidMessage(f"event seq {event['seq']} after {self.last_event_seq}")
                    if len(self._events) >= self._event_limit:
                        raise CapacityError("event queue overflow; unread events lost on disconnect")
                    self.last_event_seq = event["seq"]
                    self._events.append(event)
                elif message["type"] == "observation":
                    if self._observation is not None:
                        self.observations_coalesced += 1
                    self._observation = message
                elif message["type"] in ("error", "inventory_result"):
                    reply = cast(Reliable, message)
                    request_id = reply.get("id")
                    future = self._pending.pop(request_id, None) if isinstance(request_id, str) else None
                    if future is not None and not future.done():
                        future.set_result(reply)
                    else:
                        if len(self._reliable) >= self._reliable_limit:
                            raise CapacityError("reliable reply queue overflow; unread replies lost on disconnect")
                        self._reliable.append(reply)
                else:
                    raise InvalidMessage("unexpected hello after handshake")
                self._changed.set()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            cause = exc
        finally:
            self._finish(Disconnect(self._socket.close_code,
                                    self._socket.close_reason or str(cause or "connection closed"), cause))
            await self._socket.close()

    async def next_observation(self, timeout: float | None = None) -> Observation:
        async with asyncio.timeout(timeout):
            while True:
                # Do not present stale state as live after disconnect.
                self._active()
                if self._observation is not None:
                    result, self._observation = self._observation, None
                    return result
                self._changed.clear()
                await self._changed.wait()

    async def next_reply(self, timeout: float | None = None) -> Reliable:
        async with asyncio.timeout(timeout):
            while True:
                if self._reliable:
                    return self._reliable.popleft()
                self._active()
                self._changed.clear()
                await self._changed.wait()

    async def next_event(self, timeout: float | None = None) -> Event:
        """Next subscribed event, in ``seq`` order. Received events remain readable after closure."""
        async with asyncio.timeout(timeout):
            while True:
                if self._events:
                    return self._events.popleft()
                self._active()
                self._changed.clear()
                await self._changed.wait()

    async def events(self) -> AsyncIterator[Event]:
        while True:
            yield await self.next_event()

    async def observations(self) -> AsyncIterator[Observation]:
        while True:
            yield await self.next_observation()

    async def replies(self) -> AsyncIterator[Reliable]:
        while True:
            yield await self.next_reply()

    async def wait_closed(self) -> Disconnect:
        await self._closed.wait()
        assert self._outcome is not None
        return self._outcome

    async def _send(self, message: Command) -> None:
        self._active()
        if self.role == "observer" and message["type"] != "configure":
            raise RoleError("observer sessions may only configure")
        raw = json.dumps(message, allow_nan=False, separators=(",", ":"))
        if len(raw.encode("utf-8")) > 65536:
            raise ValueError("command exceeds 64 KiB")
        try:
            # Bound a stalled write; close if delivery is uncertain.
            async with asyncio.timeout(self._request_timeout):
                await self._socket.send(raw)
        except (ConnectionClosed, TimeoutError) as exc:
            self._finish(Disconnect(self._socket.close_code, "send failed; outcome unknown", exc))
            await self._socket.close()
            assert self._outcome is not None
            raise Disconnected(self._outcome) from exc
        except asyncio.CancelledError:
            await self.close()
            raise

    async def input(self, **fields: Unpack[InputFields]) -> None:
        message = cast(Input, {"type": "input", **fields})
        validate(message, Input)
        if set(fields) - InputFields.__annotations__.keys():
            raise ValueError("unknown input field")
        if "tap" in fields:
            self.require("tap")
        if any(k in fields for k in ("attack", "use", "hotbar")) or any(
                k in ("attack", "use") for k in fields.get("tap", [])):
            self.require("interact")
        if "hotbar" in fields and not 0 <= fields["hotbar"] <= 8:
            raise ValueError("hotbar must be 0–8")
        await self._send(message)

    async def look(self, **fields: Unpack[LookFields]) -> None:
        message = cast(Look, {"type": "look", **fields})
        validate(message, Look)
        if set(fields) - LookFields.__annotations__.keys():
            raise ValueError("unknown look field")
        mode = fields.get("mode", "instant")
        angles = "yaw" in fields and "pitch" in fields
        point = all(k in fields for k in ("x", "y", "z"))
        if mode != "instant":
            self.require("camera")
        if mode == "smooth":
            if angles == point or (angles and any(k in fields for k in ("x", "y", "z"))) or (
                    point and any(k in fields for k in ("yaw", "pitch"))):
                raise ValueError("smooth look requires either yaw/pitch or x/y/z")
        elif not angles or any(k in fields for k in ("x", "y", "z")):
            raise ValueError("instant/delta look requires yaw/pitch")
        if "speed" in fields:
            positive(fields["speed"], "speed")
        await self._send(message)

    async def release(self) -> None:
        await self._send({"type": "release"})

    async def configure(self, *, rate_divisor: int | None = None,
                        sections: list[Section] | None = None,
                        events: bool | None = None) -> None:
        self.require("configure")
        message: Configure = {"type": "configure"}
        if events is not None:
            self.require("events")
            if type(events) is not bool:
                raise ValueError("events must be a boolean")
            message["events"] = events
        if rate_divisor is not None:
            if type(rate_divisor) is not int or not 1 <= rate_divisor <= 100:
                raise ValueError("rate_divisor must be an integer 1–100")
            message["rateDivisor"] = rate_divisor
        if sections is not None:
            self.require(*section_capabilities(sections))
            message["sections"] = sections
        validate(message, Configure)
        await self._send(message)

    async def inventory(self, op: Operation, *, menu: MenuRef | None = None,
                        source: SlotRef | None = None, destination: SlotRef | None = None,
                        hotbar: int | None = None, all: bool = False, animated: bool = False,
                        timeout: float | None = None) -> InventoryResult:
        """Correlate one request. Timeout/cancellation does not cancel server work.

        IDs are unique for this session; late responses go to next_reply().
        A caller cancellation also leaves any subsequently arriving reply there.
        """
        self.require("inventory")
        if animated:
            self.require("inventoryAnimation")
        self._active()
        if self.role != "controller":
            raise RoleError("inventory requires controller role")
        if len(self._pending) >= self._pending_limit:
            raise CapacityError("too many pending inventory requests")
        wait = self._request_timeout if timeout is None else timeout
        positive(wait, "timeout")
        self._sequence += 1
        request_id = f"inventory-{self._sequence}"
        message: InventoryRequest = {"type": "inventory", "id": request_id, "op": op,
                                     "all": all, "animated": animated}
        if menu is not None:
            message["menu"] = menu
        if source is not None:
            message["from"] = source
        if destination is not None:
            message["to"] = destination
        if hotbar is not None:
            message["hotbar"] = hotbar
        validate(message, InventoryRequest)
        if op not in ("open", "inspect") and menu is None:
            raise ValueError("operation requires current menu reference")
        if menu is not None and (menu["containerId"] < 0 or menu["stateId"] < 0):
            raise ValueError("menu ids must be nonnegative")
        if op in ("move", "swap", "equip", "drop") and source is None:
            raise ValueError("operation requires source")
        if op == "move" and destination is None:
            raise ValueError("move requires destination")
        if op == "swap" and hotbar is None:
            raise ValueError("swap requires hotbar")
        if hotbar is not None and not 0 <= hotbar <= 8:
            raise ValueError("hotbar must be 0–8")
        future: asyncio.Future[Reliable | Disconnect] = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        consumed = False
        try:
            await self._send(message)
            try:
                async with asyncio.timeout(wait):
                    reply = await asyncio.shield(future)
            except TimeoutError as exc:
                raise RequestTimeout(request_id) from exc
            consumed = True
            if isinstance(reply, Disconnect):
                raise Disconnected(reply)
            if reply["type"] == "error":
                raise ServerError(reply)
            if reply["op"] != op:
                await self.close()
                raise InvalidMessage("inventory result operation does not match request")
            return reply
        finally:
            self._pending.pop(request_id, None)
            # Preserve a reply which won a race with caller timeout/cancellation.
            if future.done() and not future.cancelled():
                result = future.result()
                if not isinstance(result, Disconnect) and not consumed:
                    if len(self._reliable) >= self._reliable_limit:
                        self._finish(Disconnect(None, "late reply queue overflow", CapacityError()))
                        await self._socket.close()
                    else:
                        self._reliable.append(result)
                        self._changed.set()
            future.cancel()

    async def close(self) -> None:
        """Close transport; server controller-loss safety releases all controls.

        No successful action/release acknowledgment is implied. Idempotent.
        """
        await self._socket.close()
        await self._reader


@asynccontextmanager
async def connect(uri: str = "ws://127.0.0.1:24680/", *, role: Role = "controller",
                  sections: list[Section] | None = None,
                  required_capabilities: Iterable[str] = (), handshake_timeout: float = 5,
                  request_timeout: float = 10, reliable_limit: int = 64,
                  pending_limit: int = 32, events: bool = False,
                  event_limit: int = 1024) -> AsyncIterator[Client]:
    """Open exactly one protocol-2 session, validating hello before yielding.

    ``events=True`` subscribes in hello and requires the ``events`` capability.

    No Origin/proxy/compression; automatic WebSocket pong handling stays active.
    Keep the asyncio loop responsive. Do CPU/blocking work in another thread.
    """
    positive(handshake_timeout, "handshake_timeout")
    positive(request_timeout, "request_timeout")
    for value in (reliable_limit, pending_limit, event_limit):
        if type(value) is not int or not 1 <= value <= 1024:
            raise ValueError("queue bounds must be integers 1–1024")
    if role not in ("controller", "observer"):
        raise RoleError(f"unsupported role: {role}")
    required = set(required_capabilities)
    if role == "observer":
        required.add("observer")
    request: dict[str, object] = {"type": "hello", "versions": [PROTOCOL_VERSION], "role": role}
    if sections is not None:
        validate(sections, list[Section])
        request["sections"] = sections
        required.update(section_capabilities(sections))
    if type(events) is not bool:
        raise ValueError("events must be a boolean")
    if events:
        request["events"] = True
        required.add("events")
    async with ws_connect(uri, origin=None, proxy=None, compression=None,
                          open_timeout=handshake_timeout, close_timeout=1,
                          ping_interval=None, max_size=131072, max_queue=16,
                          write_limit=32768) as socket:
        async with asyncio.timeout(handshake_timeout):
            await socket.send(json.dumps(request))
            try:
                hello = decode(await socket.recv())
            except ConnectionClosed as exc:
                raise Disconnected(Disconnect(socket.close_code, socket.close_reason or "handshake closed", exc)) from exc
        if hello["type"] == "error":
            raise ServerError(hello)
        if hello["type"] != "hello":
            raise InvalidMessage("expected hello reply")
        if hello["version"] != PROTOCOL_VERSION:
            raise VersionError(f"unsupported selected protocol {hello['version']}; expected {PROTOCOL_VERSION}")
        client = Client(socket, hello, role, reliable_limit, pending_limit, request_timeout, event_limit)
        try:
            client.require(*required)
            yield client
        finally:
            # Finish cleanup even when the body is cancelled.
            cleanup = asyncio.create_task(client.close())
            try:
                await asyncio.shield(cleanup)
            except asyncio.CancelledError:
                with suppress(Exception):
                    await cleanup
                raise
