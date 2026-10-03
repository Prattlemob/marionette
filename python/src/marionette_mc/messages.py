"""Protocol 2 wire types. Unknown additive fields are retained by decoding."""
import json
import math
import types
from typing import Literal, NotRequired, TypedDict, Union, cast, get_args, get_origin, get_type_hints, is_typeddict

PROTOCOL_VERSION = 2
Role = Literal["controller", "observer"]
Section = Literal["player"]
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


class Observation(TypedDict):
    type: Literal["observation"]
    tick: int
    player: NotRequired[Player]


class Stack(TypedDict):
    item: str
    count: int


class Slot(Stack):
    slot: int
    alias: NotRequired[str]


class MenuRef(TypedDict):
    type: str
    containerId: int
    stateId: int


class Menu(MenuRef):
    slots: list[Slot]
    carried: Stack


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


InventoryRequest = TypedDict("InventoryRequest", {
    "type": Literal["inventory"], "op": Operation, "id": NotRequired[RequestId],
    "menu": NotRequired[MenuRef], "from": NotRequired[SlotRef],
    "to": NotRequired[SlotRef], "hotbar": NotRequired[int],
    "all": NotRequired[bool], "animated": NotRequired[bool],
})
Command = Input | Look | Release | Configure | InventoryRequest
Message = Hello | Observation | InventoryResult | Error
Reliable = InventoryResult | Error


class InvalidMessage(ValueError):
    """Malformed or unsupported wire message; never a fabricated server event."""


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
    schema = {"hello": Hello, "observation": Observation,
              "inventory_result": InventoryResult, "error": Error}.get(kind)
    if schema is None:
        raise InvalidMessage("unsupported message type")
    validate(value, schema)
    return cast(Message, value)


def menu_ref(menu: Menu) -> MenuRef:
    return {"type": menu["type"], "containerId": menu["containerId"], "stateId": menu["stateId"]}
