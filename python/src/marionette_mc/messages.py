"""Protocol 2 wire types. Unknown additive fields are retained by decoding."""
import json
import math
import types
from typing import Iterator, Literal, NotRequired, TypedDict, Union, cast, get_args, get_origin, get_type_hints, is_typeddict

PROTOCOL_VERSION = 2
Role = Literal["controller", "observer"]
Section = Literal["player", "inventory", "target", "world", "entities"]
Tap = Literal["jump", "attack", "use", "swap_hands"]
Operation = Literal["open", "inspect", "move", "swap", "equip", "drop", "craft", "close"]
RequestId = str | int | float
SlotRef = str | int


class Envelope(TypedDict):
    id: NotRequired[RequestId]


class HelloRequest(Envelope):
    type: Literal["hello"]
    versions: list[int]
    role: NotRequired[Role]
    sections: NotRequired[list[Section]]
    events: NotRequired[bool]


class Hello(Envelope):
    type: Literal["hello"]
    version: int
    capabilities: dict[str, bool]
    mod: str


class Vector(TypedDict):
    x: float
    y: float
    z: float


class Experience(TypedDict):
    level: int
    progress: float
    total: int


class Effect(TypedDict):
    id: str
    duration: int
    amplifier: int


class PlayerRef(TypedDict):
    """A player's profile identity (``playerIdentity``)."""
    uuid: str
    name: str


class UsingItem(TypedDict):
    """The item being used (``playerActivity``). ``hand`` is ``"main_hand"`` or ``"off_hand"``."""
    hand: str
    item: str
    ticks: int


class Vehicle(TypedDict):
    """The ridden entity (``playerActivity``); ``id`` matches entity and target ids."""
    id: int
    type: str


class Player(Vector):
    """The ``player`` section. Fields after ``effects`` need ``playerIdentity``
    (``uuid``, ``name``) or ``playerActivity`` (the rest)."""
    yaw: float
    pitch: float
    velocity: Vector
    health: float
    maxHealth: float
    hunger: int
    saturation: float
    air: int
    maxAir: int
    xp: Experience
    onGround: bool
    inWater: bool
    sneaking: bool
    sprinting: bool
    sleeping: bool
    onFire: bool
    effects: list[Effect]
    uuid: NotRequired[str]
    name: NotRequired[str]
    swimming: NotRequired[bool]
    fallFlying: NotRequired[bool]
    blocking: NotRequired[bool]
    usingItem: NotRequired[UsingItem | None]
    vehicle: NotRequired[Vehicle | None]


class Enchantment(TypedDict):
    id: str
    level: int


class Stack(TypedDict):
    """``item``/``count`` always; the enumerated extras only when present (``inventoryState``)."""
    item: str
    count: int
    damage: NotRequired[int]
    maxDamage: NotRequired[int]
    name: NotRequired[str]
    nameTruncated: NotRequired[bool]
    enchantments: NotRequired[list[Enchantment]]
    enchantmentsTruncated: NotRequired[bool]
    storedEnchantments: NotRequired[list[Enchantment]]
    storedEnchantmentsTruncated: NotRequired[bool]
    potion: NotRequired[str]
    charged: NotRequired[bool]


SlotRefusal = Literal["crafting", "result", "inactive", "bundle"]
MenuRefusal = Literal["player_unavailable", "unsupported_menu", "busy", "cursor_occupied"]
MutatingOperation = Literal["move", "swap", "equip", "drop", "craft", "close"]
SupportScope = Literal["player", "storage", "crafting", "processing"]
SupportReason = Literal["click_behavior", "menu_data", "slot_behavior", "shared_slots"]
RejectionReason = Literal[
    "no_world", "player_unavailable", "screen_open", "no_menu", "unsupported_menu",
    "cursor_occupied", "slot_out_of_range", "alias_absent", "same_slot", "slot_refused",
    "source_empty", "source_locked", "destination_mismatch", "destination_locked",
    "destination_rejects", "destination_full", "source_rejects", "source_full",
    "not_armor", "armor_occupied", "armor_count", "drop_forbidden", "unexpected_click",
    "count_exceeds_source", "whole_stack_only", "not_crafting", "no_result", "missing_ingredients",
    "remainder_unsupported", "released", "human_input", "menu_changed", "contents_changed",
    "world_exit", "result_changed", "human_paused"]


class Slot(Stack):
    slot: int
    alias: NotRequired[str]
    refused: NotRequired[str]


class MenuSupport(TypedDict):
    """Mutation support (``inventoryStorage``). ``scope`` is ``SupportScope`` or ``None``
    (never mutated); ``reasons`` lists failed rules, known values ``SupportReason``.
    Both are open strings so a future value does not end the session."""
    scope: str | None
    reasons: list[str]


class Crafting(TypedDict):
    """Crafting grid of a menu (``crafting``): result slot and row-major grid slot indices."""
    result: int
    grid: list[int]
    width: int
    height: int


class Processing(TypedDict):
    """Furnace-style menu (``crafting``): slot roles and synchronized progress in ticks.

    ``kind`` is an open string (``"furnace"`` today); ``smeltable`` is ``None`` for an
    empty input slot.
    """
    kind: str
    input: int
    fuel: int
    result: int
    burnTime: int
    burnDuration: int
    cookTime: int
    cookDuration: int
    lit: bool
    smeltable: bool | None


class MenuRef(TypedDict):
    type: str
    containerId: int
    stateId: int


class Menu(MenuRef):
    """Menu descriptor. Fields after ``carried`` require ``inventoryState``.

    ``refusal`` and ``refused`` are strings rather than closed literals so a
    future reason does not end the session; known values are ``MenuRefusal``
    and ``SlotRefusal``.
    """
    slots: list[Slot]
    carried: Stack
    slotCount: NotRequired[int]
    operations: NotRequired[list[str]]
    refusal: NotRequired[str | None]
    support: NotRequired[MenuSupport]
    crafting: NotRequired[Crafting]
    processing: NotRequired[Processing]
    reduced: NotRequired[bool]
    truncated: NotRequired[bool]


class Armor(TypedDict):
    head: Stack
    chest: Stack
    legs: Stack
    feet: Stack


class Inventory(TypedDict):
    """The ``inventory`` observation section (``inventoryState``)."""
    selected: int
    mainHand: Stack
    hotbar: list[Stack]
    main: list[Stack]
    armor: Armor
    offhand: Stack
    menu: Menu | None
    reduced: NotRequired[bool]


class BlockPos(TypedDict):
    x: int
    y: int
    z: int


class Reach(TypedDict):
    block: float
    entity: float


TargetKind = Literal["block", "entity", "none"]
Face = Literal["down", "up", "north", "south", "west", "east"]


class Target(TypedDict):
    """The ``target`` section (``targetState``): vanilla's crosshair hit result.

    ``kind`` is a ``TargetKind`` but stays an open string; treat unknown kinds
    as ``"none"``. Blocks add ``pos``, ``block`` and ``face``; entities add
    ``id`` and ``entity``; both add ``hit`` and ``distance``. ``reach`` is always
    present: nothing beyond it is ever a target.
    """
    kind: str
    reach: Reach
    pos: NotRequired[BlockPos]
    block: NotRequired[str]
    face: NotRequired[str]
    id: NotRequired[int]
    entity: NotRequired[str]
    hit: NotRequired[Vector]
    distance: NotRequired[float]


class Light(TypedDict):
    block: int
    sky: int
    combined: int
    effective: int


Weather = Literal["clear", "rain", "thunder"]


class World(TypedDict):
    """The ``world`` section (``worldState``). ``weather`` is a ``Weather`` but stays open."""
    dimension: str
    dayTime: int
    timeOfDay: int
    day: int
    weather: str
    rainLevel: float
    thunderLevel: float
    feet: BlockPos
    light: Light


Hostility = Literal["hostile", "neutral", "passive", "player", "item", "other"]
TargetingMe = Literal["yes", "no", "unknown"]


class DroppedItem(TypedDict):
    item: str
    count: int


class Entity(Vector):
    """One ``entities`` entry. ``hostility`` is a ``Hostility`` and ``targetingMe``
    a ``TargetingMe``, but both stay open strings: treat unknown values as
    ``"other"`` and ``"unknown"``. Living entities add ``health``, ``maxHealth``
    and ``targetingMe``; players ``name`` (and ``uuid`` with ``playerIdentity``);
    dropped items ``item``.
    """
    id: int
    type: str
    hostility: str
    velocity: Vector
    distance: float
    health: NotRequired[float]
    maxHealth: NotRequired[float]
    targetingMe: NotRequired[str]
    name: NotRequired[str]
    uuid: NotRequired[str]
    item: NotRequired[DroppedItem]


class Entities(TypedDict):
    """The ``entities`` section (``entityState``): nearest first, at most ``maxCount``
    of the ``total`` entities within ``radius``; ``truncated`` when some were left out.
    """
    radius: int
    maxCount: int
    total: int
    truncated: bool
    nearby: list[Entity]


class Observation(TypedDict):
    type: Literal["observation"]
    tick: int
    player: NotRequired[Player]
    inventory: NotRequired[Inventory]
    target: NotRequired[Target]
    world: NotRequired[World]
    entities: NotRequired[Entities]


class InventoryResult(Envelope):
    type: Literal["inventory_result"]
    op: Operation
    menu: Menu | None


ScanReason = Literal["no_world", "over_radius", "over_volume", "busy",
                     "released", "world_exit", "level_changed", "too_large"]
MAX_SCAN_VOLUME = 8192


class ScanLimits(TypedDict):
    """The caps in force, sent with ``over_radius``/``over_volume`` refusals."""
    radius: int
    maxVolume: int


class ScanResult(Envelope):
    """A ``scan_result`` (``blockScan``): ``palette`` plus y→z→x ``indices``.

    A ``None`` palette entry means the client had no block data (unloaded chunk).
    Use ``scan_block``/``scan_blocks`` rather than decoding the order by hand.
    """
    type: Literal["scan_result"]
    dimension: str
    min: BlockPos
    size: BlockPos
    order: str
    startTick: int
    tick: int
    palette: list[str | None]
    indices: list[int]


ActionKind = Literal["respawn", "chat", "command"]
RespawnReason = Literal["no_world", "not_dead", "hardcore", "human_paused"]
ChatReason = Literal["no_world", "client_restricted", "chat_disabled", "commands_disabled", "empty",
                     "too_long", "illegal_character", "slash_prefix", "rate_limited", "human_paused"]


class ChatLimits(TypedDict):
    """The chat limits in force, sent with every ``chat_refused`` error (``chat``)."""
    maxMessages: int
    windowSeconds: int
    maxLength: int


class ActionResult(Envelope):
    """Reply to an accepted ``respawn`` or ``chat`` request: the request was sent to
    the server, not acknowledged by it. ``action`` is an ``ActionKind`` but stays open."""
    type: Literal["action_result"]
    action: str


class Error(Envelope):
    type: Literal["error"]
    code: str
    message: str
    input: NotRequired[str]
    supported: NotRequired[list[int]]
    # Inventory (inventoryStorage, see RejectionReason), scan (blockScan, see ScanReason),
    # respawn (RespawnReason) or chat (ChatReason) reason.
    reason: NotRequired[str]
    limits: NotRequired[ScanLimits | ChatLimits]
    retryAfterMs: NotRequired[int]


class InputFields(TypedDict, total=False):
    forward: bool
    back: bool
    left: bool
    right: bool
    jump: bool
    sneak: bool
    sprint: bool
    attack: bool
    use: bool
    tap: list[Tap]
    hotbar: int


class Input(InputFields, Envelope):
    type: Literal["input"]


class LookFields(TypedDict, total=False):
    mode: Literal["instant", "delta", "smooth"]
    yaw: float
    pitch: float
    x: float
    y: float
    z: float
    speed: float


class Look(LookFields, Envelope):
    type: Literal["look"]


class Release(Envelope):
    type: Literal["release"]


class Configure(Envelope):
    type: Literal["configure"]
    rateDivisor: NotRequired[int]
    sections: NotRequired[list[Section]]
    events: NotRequired[bool]


Basis = Literal["server", "client"]


class EventEnvelope(TypedDict):
    """Fields on every event. Events never carry ``id`` and never answer requests."""
    type: Literal["event"]
    seq: int
    worldSession: str
    tick: int
    basis: Basis


class DamageSource(TypedDict):
    type: str | None
    attacker: str | None
    direct: str | None
    attackerPlayer: NotRequired[PlayerRef | None]


class DamageEvent(EventEnvelope):
    event: Literal["damage"]
    source: DamageSource | None
    amount: float | None
    health: float | None


class DeathEvent(EventEnvelope):
    event: Literal["death"]
    message: str | None
    truncated: bool


class RespawnEvent(EventEnvelope):
    event: Literal["respawn"]
    dimension: str | None


DimensionChangeEvent = TypedDict("DimensionChangeEvent", {
    "type": Literal["event"], "seq": int, "worldSession": str, "tick": int, "basis": Basis,
    "event": Literal["dimension_change"], "from": str | None, "to": str | None,
})


class ItemPickupEvent(EventEnvelope):
    event: Literal["item_pickup"]
    item: str | None
    count: int


class ChatEvent(EventEnvelope):
    event: Literal["chat"]
    kind: Literal["chat", "system", "action_bar"]
    text: str | None
    sender: str | None
    senderName: NotRequired[str | None]
    chatType: str | None
    truncated: bool


class BlockBrokenEvent(EventEnvelope):
    event: Literal["block_broken"]
    block: str | None
    pos: BlockPos


ControlMode = Literal["human_priority", "agent_exclusive", "panic"]
ControlCause = Literal["controller_attached", "human_input", "human_idle", "lockout_engaged",
                       "lockout_released", "controller_lost", "panic", "rearmed"]
HumanInputKind = Literal["movement", "jump", "sneak", "sprint", "look", "attack", "use", "hotbar",
                         "drop", "swap_hands", "pick_block", "pause_menu"]


class ControlEvent(EventEnvelope):
    """Local human precedence changed (``humanPrecedence``). While ``paused`` the mod
    discards ``input``/``look`` and refuses actuation with reason ``human_paused``.
    ``cause`` and ``inputs`` are ``ControlCause``/``HumanInputKind`` but stay open."""
    event: Literal["control"]
    mode: str
    paused: bool
    cause: str
    inputs: list[str]


class OtherEvent(EventEnvelope):
    """A kind this client does not know yet; ignore it but keep its ``seq``."""
    event: str


Event = (DamageEvent | DeathEvent | RespawnEvent | DimensionChangeEvent | ItemPickupEvent
         | ChatEvent | BlockBrokenEvent | ControlEvent | OtherEvent)
EVENT_SCHEMAS: dict[str, object] = {
    "damage": DamageEvent, "death": DeathEvent, "respawn": RespawnEvent,
    "dimension_change": DimensionChangeEvent, "item_pickup": ItemPickupEvent,
    "chat": ChatEvent, "block_broken": BlockBrokenEvent, "control": ControlEvent,
}


InventoryRequest = TypedDict("InventoryRequest", {
    "type": Literal["inventory"], "op": Operation, "id": NotRequired[RequestId],
    "menu": NotRequired[MenuRef], "from": NotRequired[SlotRef],
    "to": NotRequired[SlotRef], "hotbar": NotRequired[int],
    "all": NotRequired[bool], "animated": NotRequired[bool], "count": NotRequired[int],
})


class ScanRequest(Envelope):
    type: Literal["scan"]
    size: BlockPos
    min: NotRequired[BlockPos]


class RespawnRequest(Envelope):
    type: Literal["respawn"]


class ChatRequest(Envelope):
    """Exactly one of ``text`` (ordinary chat) and ``command`` (needs the human to enable commands)."""
    type: Literal["chat"]
    text: NotRequired[str]
    command: NotRequired[str]


Command = Input | Look | Release | Configure | InventoryRequest | ScanRequest | RespawnRequest | ChatRequest
Message = Hello | Observation | InventoryResult | ScanResult | ActionResult | Error | Event
Reliable = InventoryResult | ScanResult | ActionResult | Error


class InvalidMessage(ValueError):
    """Malformed or unsupported wire message; never a fabricated server event."""


class UnsupportedMessage(InvalidMessage):
    """A well-formed message whose ``type`` this client does not know."""


def validate(value: object, schema: object, path: str = "message") -> None:
    """Validate required fields and known field types, permitting additive keys."""
    origin, args = get_origin(schema), get_args(schema)
    if origin is NotRequired:
        validate(value, args[0], path)
    elif origin in (Union, types.UnionType):
        for alternative in args:
            try:
                validate(value, alternative, path)
                return
            except InvalidMessage:
                pass
        raise InvalidMessage(f"{path}: invalid value {value!r}")
    elif origin is Literal:
        if not any(type(value) is type(a) and value == a for a in args):
            raise InvalidMessage(f"{path}: expected {args}")
    elif is_typeddict(schema):
        if not isinstance(value, dict):
            raise InvalidMessage(f"{path}: expected object")
        hints = get_type_hints(schema, include_extras=True)
        required = getattr(schema, "__required_keys__")
        if not required <= value.keys():
            raise InvalidMessage(f"{path}: missing {sorted(required - value.keys())}")
        for key, item in value.items():
            if key in hints:
                validate(item, hints[key], f"{path}.{key}")
    elif origin is list:
        if not isinstance(value, list):
            raise InvalidMessage(f"{path}: expected array")
        for item in value:
            validate(item, args[0], path)
    elif origin is dict:
        if not isinstance(value, dict):
            raise InvalidMessage(f"{path}: expected object")
        for key, item in value.items():
            validate(key, args[0], path)
            validate(item, args[1], path)
    elif schema is float:
        if type(value) not in (int, float) or not math.isfinite(cast(float, value)):
            raise InvalidMessage(f"{path}: expected finite number")
    elif schema is type(None):
        if value is not None:
            raise InvalidMessage(f"{path}: expected null")
    elif type(value) is not schema:
        raise InvalidMessage(f"{path}: invalid type")


def decode(raw: str | bytes) -> Message:
    if not isinstance(raw, str):
        raise InvalidMessage("binary frames are unsupported")
    try:
        value = json.loads(raw, parse_constant=lambda s: (_ for _ in ()).throw(InvalidMessage(s)))
    except (ValueError, RecursionError) as exc:
        raise InvalidMessage(str(exc)) from exc
    if not isinstance(value, dict):
        raise InvalidMessage("expected object")
    kind = value.get("type")
    if not isinstance(kind, str):
        raise InvalidMessage("type must be a string")
    schema: object = {"hello": Hello, "observation": Observation,
                      "inventory_result": InventoryResult, "scan_result": ScanResult,
                      "action_result": ActionResult, "error": Error}.get(kind)
    if kind == "event":
        event = value.get("event")
        if not isinstance(event, str):
            raise InvalidMessage("event must be a string")
        schema = EVENT_SCHEMAS.get(event, OtherEvent)
        if "id" in value:
            raise InvalidMessage("events never carry an id")
    if schema is None:
        raise UnsupportedMessage(f"unsupported message type {kind!r}")
    validate(value, schema)
    return cast(Message, value)


def check_scan(result: ScanResult) -> None:
    """Raise ``InvalidMessage`` unless ``indices`` covers the box and points into ``palette``."""
    size = result["size"]
    if result["order"] != "yzx" or size["x"] < 1 or size["y"] < 1 or size["z"] < 1:
        raise InvalidMessage("unsupported scan order or empty box")
    if len(result["indices"]) != size["x"] * size["y"] * size["z"]:
        raise InvalidMessage("scan indices do not cover the box")
    if any(type(i) is not int or not 0 <= i < len(result["palette"]) for i in result["indices"]):
        raise InvalidMessage("scan index outside the palette")


def scan_block(result: ScanResult, x: int, y: int, z: int) -> str | None:
    """Block id at absolute ``(x, y, z)``; ``None`` where the client had no data.

    Raises ``KeyError`` for a position outside the scanned box.
    """
    low, size = result["min"], result["size"]
    dx, dy, dz = x - low["x"], y - low["y"], z - low["z"]
    if not (0 <= dx < size["x"] and 0 <= dy < size["y"] and 0 <= dz < size["z"]):
        raise KeyError((x, y, z))
    return result["palette"][result["indices"][(dy * size["z"] + dz) * size["x"] + dx]]


def scan_blocks(result: ScanResult) -> Iterator[tuple[int, int, int, str | None]]:
    """Every ``(x, y, z, block)`` in wire (y→z→x) order."""
    low, size = result["min"], result["size"]
    palette, indices = result["palette"], result["indices"]
    for i, index in enumerate(indices):
        dx = i % size["x"]
        dz = i // size["x"] % size["z"]
        dy = i // (size["x"] * size["z"])
        yield low["x"] + dx, low["y"] + dy, low["z"] + dz, palette[index]


def menu_ref(menu: Menu) -> MenuRef:
    return {"type": menu["type"], "containerId": menu["containerId"], "stateId": menu["stateId"]}
