"""Protocol 2 wire types. Unknown additive fields are retained by decoding."""
import json
import math
import types
from typing import Literal, NotRequired, TypedDict, Union, cast, get_args, get_origin, get_type_hints, is_typeddict

PROTOCOL_VERSION = 2
Role = Literal["controller", "observer"]
Section = Literal["player", "inventory"]
Tap = Literal["jump", "attack", "use"]
Operation = Literal["open", "inspect", "move", "swap", "equip", "drop", "close"]
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


class Player(Vector):
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


SlotRefusal = Literal["crafting", "inactive", "bundle"]
MenuRefusal = Literal["player_unavailable", "unsupported_menu", "busy", "cursor_occupied"]
MutatingOperation = Literal["move", "swap", "equip", "drop", "close"]
SupportScope = Literal["player", "storage"]
SupportReason = Literal["click_behavior", "menu_data", "slot_behavior", "shared_slots"]
RejectionReason = Literal[
    "no_world", "player_unavailable", "screen_open", "no_menu", "unsupported_menu",
    "cursor_occupied", "slot_out_of_range", "alias_absent", "same_slot", "slot_refused",
    "source_empty", "source_locked", "destination_mismatch", "destination_locked",
    "destination_rejects", "destination_full", "source_rejects", "source_full",
    "not_armor", "armor_occupied", "armor_count", "drop_forbidden", "unexpected_click",
    "released", "human_input", "menu_changed", "contents_changed", "world_exit"]


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


class Observation(TypedDict):
    type: Literal["observation"]
    tick: int
    player: NotRequired[Player]
    inventory: NotRequired[Inventory]


class InventoryResult(Envelope):
    type: Literal["inventory_result"]
    op: Operation
    menu: Menu | None


class Error(Envelope):
    type: Literal["error"]
    code: str
    message: str
    input: NotRequired[str]
    supported: NotRequired[list[int]]
    reason: NotRequired[str]  # inventory rejection reason (inventoryStorage); see RejectionReason


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
    chatType: str | None
    truncated: bool


class BlockPos(TypedDict):
    x: int
    y: int
    z: int


class BlockBrokenEvent(EventEnvelope):
    event: Literal["block_broken"]
    block: str | None
    pos: BlockPos


class OtherEvent(EventEnvelope):
    """A kind this client does not know yet; ignore it but keep its ``seq``."""
    event: str


Event = (DamageEvent | DeathEvent | RespawnEvent | DimensionChangeEvent | ItemPickupEvent
         | ChatEvent | BlockBrokenEvent | OtherEvent)
EVENT_SCHEMAS: dict[str, object] = {
    "damage": DamageEvent, "death": DeathEvent, "respawn": RespawnEvent,
    "dimension_change": DimensionChangeEvent, "item_pickup": ItemPickupEvent,
    "chat": ChatEvent, "block_broken": BlockBrokenEvent,
}


InventoryRequest = TypedDict("InventoryRequest", {
    "type": Literal["inventory"], "op": Operation, "id": NotRequired[RequestId],
    "menu": NotRequired[MenuRef], "from": NotRequired[SlotRef],
    "to": NotRequired[SlotRef], "hotbar": NotRequired[int],
    "all": NotRequired[bool], "animated": NotRequired[bool],
})
Command = Input | Look | Release | Configure | InventoryRequest
Message = Hello | Observation | InventoryResult | Error | Event
Reliable = InventoryResult | Error


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
                      "inventory_result": InventoryResult, "error": Error}.get(kind)
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


def menu_ref(menu: Menu) -> MenuRef:
    return {"type": menu["type"], "containerId": menu["containerId"], "stateId": menu["stateId"]}
