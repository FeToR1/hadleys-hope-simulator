#!/usr/bin/env python3
"""Independent second implementation of the stack VM (docs/integration-v1.md) that replays the vectors.

It is written from the artifact description, not by porting the Kotlin code, so a vector that passes here and in
Kotlin says the description is enough to implement a VM. Usage: python run_vectors.py [DIRECTORY_WITH_VECTORS]
"""
import hashlib, json, math, struct, sys
from pathlib import Path

I64_MIN, I64_MAX = -(2 ** 63), 2 ** 63 - 1
INSTRUCTION_BUDGET, STACK_LIMIT, OUTPUT_LIMIT, LIST_LIMIT = 100_000, 4096, 1024, 4096
UNSET = object()   # a slot that was never assigned; null is a legitimate value (Option none)


class VmError(Exception):
    pass


def is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def number(v):
    if not is_number(v):
        raise VmError("expected a number")
    f = float(v)
    if not math.isfinite(f):
        raise VmError("non-finite number")
    return f


def finite(f):
    if not math.isfinite(f):
        raise VmError("non-finite result")
    return f


def i64(v):
    if not (I64_MIN <= v <= I64_MAX):
        raise VmError("Int64 overflow")
    return v


def draw(seed, entity, behavior, rule, site, counter):
    data = struct.pack(">q", seed)
    for text in (entity, behavior, rule, site):
        raw = text.encode("utf-8")
        data += struct.pack(">i", len(raw)) + raw
    data += struct.pack(">q", counter)
    return (int.from_bytes(hashlib.sha256(data).digest()[:8], "big") >> 11) / 2 ** 53


def equal(a, b):
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool) and a == b
    return a == b   # numbers compare by value (2 == 2.0); strings, null, records and lists structurally


def binary(op, kind, left, right):
    if op in ("EQ", "NEQ"):
        return equal(left, right) == (op == "EQ")
    if op in ("LT", "LE", "GT", "GE"):
        if not (is_number(left) and is_number(right)):
            raise VmError("ordering needs numbers")
        return {"LT": left < right, "LE": left <= right, "GT": left > right, "GE": left >= right}[op]
    if kind in ("Int64", "Money"):
        if not (isinstance(left, int) and isinstance(right, int)) or isinstance(left, bool) or isinstance(right, bool):
            raise VmError("integer operands expected")
        if op == "ADD":
            return i64(left + right)
        if op == "SUB":
            return i64(left - right)
        if op == "MUL":
            return i64(left * right)
        if op == "MOD":
            if right == 0:
                raise VmError("modulo by zero")
            magnitude = abs(left) % abs(right)          # the remainder takes the sign of the dividend
            return -magnitude if left < 0 else magnitude
        raise VmError("invalid integer operation")
    l, r = number(left), number(right)
    if op == "ADD":
        return finite(l + r)
    if op == "SUB":
        return finite(l - r)
    if op == "MUL":
        return finite(l * r)
    if op == "DIV":
        if r == 0.0:
            raise VmError("division by zero")
        return finite(l / r)
    raise VmError("invalid arithmetic operation " + op)


def convert(value, kind):
    if kind in ("Real64", "Probability"):
        return finite(number(value))
    if kind.startswith("Option<") and value is not None:
        return {"some": convert(value["some"], kind[len("Option<"):-1])}
    return value


EFFECT_ARITY = {"POWER_REQUEST": 1, "REPAIR_REQUEST": 1, "DAMAGE_REQUEST": 3, "MOTION_REQUEST": 2}


class Vm:
    def __init__(self, program, entity, behavior, params, seed):
        self.entity, self.seed = entity, seed
        self.b = next(b for b in program["behaviors"] if b["name"] == behavior)
        self.params = [params[slot["name"]] for slot in self.b["params"]]
        self.state = [UNSET] * len(self.b["state"])
        self.counters, self.sequence = {}, 0
        self.step_seconds = float(program["stepSeconds"])
        self.remaining = INSTRUCTION_BUDGET
        self.run(self.b["initialize"], 0, "__init__", {"tick": 0, "view": {}}, {}, [], [])
        if any(v is UNSET for v in self.state):
            raise VmError("uninitialized state")

    def snapshot(self):
        return {slot["name"]: self.state[i] for i, slot in enumerate(self.b["state"])}

    def step(self, tick, view, events):
        before = (list(self.state), dict(self.counters), self.sequence)
        intents, out = [], []
        self.remaining = INSTRUCTION_BUDGET
        frame = {"tick": tick, "view": view}
        try:
            for event in sorted(events, key=lambda e: (e["sender"], e["sequence"])):
                for h in self.b["handlers"]:
                    if h.get("eventId") == event["eventId"]:
                        self.run(h["entry"], h["localCount"], h["name"], frame, event["fields"], intents, out)
            for h in self.b["handlers"]:
                if h.get("periodTicks") is not None and tick % h["periodTicks"] == 0:
                    self.run(h["entry"], h["localCount"], h["name"], frame, {}, intents, out)
            return {"intents": intents, "events": out, "state": self.snapshot()}
        except VmError:
            self.state, self.counters, self.sequence = before
            raise

    def random(self, rule, site):
        n = self.counters.get((rule, site), 0)
        self.counters[(rule, site)] = n + 1
        return draw(self.seed, self.entity, self.b["name"], rule, site, n)

    def run(self, entry, local_count, rule, frame, message, intents, out):
        code, stack = self.b["code"], []
        temps, locals_ = [UNSET] * self.b["temporaryCount"], [UNSET] * local_count

        def pop():
            if not stack:
                raise VmError("stack underflow")
            return stack.pop()

        def take(n):
            return [pop() for _ in range(n)][::-1]

        def load(slots, index, what):
            if slots[index] is UNSET:
                raise VmError("uninitialized " + what)
            stack.append(slots[index])

        pc = entry
        while True:
            self.remaining -= 1
            if self.remaining < 0:
                raise VmError("instruction budget exceeded")
            if len(stack) > STACK_LIMIT or len(intents) + len(out) > OUTPUT_LIMIT:
                raise VmError("limit exceeded")
            ins = code[pc]
            op, arg, text, kind = ins["op"], ins.get("arg", 0), ins.get("text", ""), ins.get("type", "")
            nxt = pc + 1
            if op == "CONST":
                stack.append(ins.get("value"))
            elif op == "LOAD_TEMP":
                load(temps, arg, "temporary")
            elif op == "STORE_TEMP":
                temps[arg] = pop()
            elif op == "LOAD_PARAM":
                stack.append(self.params[arg])
            elif op == "LOAD_STATE":
                load(self.state, arg, "state")
            elif op == "STORE_STATE":
                self.state[arg] = pop()
            elif op == "LOAD_LOCAL":
                load(locals_, arg, "local")
            elif op == "STORE_LOCAL":
                locals_[arg] = pop()
            elif op == "LOAD_MESSAGE":
                stack.append(message)
            elif op == "LOAD_VIEW":
                if text not in frame["view"]:
                    raise VmError("missing observation " + text)
                stack.append(frame["view"][text])
            elif op == "LOAD_OBSERVATIONS":
                stack.append(frame["view"])
            elif op == "LOAD_TIME":
                stack.append(finite(frame["tick"] * self.step_seconds))
            elif op == "LOAD_SELF":
                stack.append(self.entity)
            elif op == "GET_FIELD":
                obj = pop()
                if not isinstance(obj, dict) or text not in obj:
                    raise VmError("missing field " + text)
                stack.append(obj[text])
            elif op == "CONVERT":
                stack.append(convert(pop(), kind))
            elif op == "UNARY":
                v = pop()
                if text == "NOT":
                    stack.append(not v)
                elif text == "PLUS":
                    stack.append(v)
                elif text == "MINUS":
                    stack.append(i64(-v) if kind in ("Int64", "Money") else finite(-number(v)))
                else:
                    raise VmError("unknown unary operator")
            elif op == "BINARY":
                right = pop()
                left = pop()
                stack.append(binary(text, kind, left, right))
            elif op == "MAKE_RECORD":
                stack.append(dict(zip(ins["names"], take(len(ins["names"])))))
            elif op == "SOME":
                stack.append({"some": pop()})
            elif op == "IS_SOME":
                stack.append(pop() is not None)
            elif op == "UNWRAP":
                v = pop()
                if not isinstance(v, dict) or "some" not in v:
                    raise VmError("unwrap of none")
                stack.append(v["some"])
            elif op == "INDEX":
                i = pop()
                lst = pop()
                if not isinstance(i, int) or not 0 <= i < len(lst):
                    raise VmError("index out of range")
                stack.append(lst[i])
            elif op == "CLAMP":
                x, lo, hi = take(3)
                if number(lo) > number(hi):
                    raise VmError("clamp bounds")
                stack.append(lo if number(x) < number(lo) else hi if number(x) > number(hi) else x)
            elif op == "NEAREST":
                lst = pop()
                if len(lst) > LIST_LIMIT:
                    raise VmError("list too long")
                stack.append(None if not lst else {"some": min(lst, key=lambda t: (number(t["distance"]), t["id"]))})
            elif op in ("CHANCE", "HAZARD"):
                site = pop()
                parameter = number(pop())
                p = parameter
                if op == "HAZARD":
                    if parameter < 0:
                        raise VmError("negative rate")
                    p = -math.expm1(-parameter * self.step_seconds)
                if not 0.0 <= p <= 1.0:
                    raise VmError("probability outside [0,1]")
                stack.append(self.random(rule, site) < p)
            elif op in EFFECT_ARITY:
                intents.append({"operation": op, "arguments": take(EFFECT_ARITY[op])})
            elif op == "SEND":
                fields = dict(zip(ins["names"], take(len(ins["names"]))))
                out.append({"target": pop(), "eventId": arg, "sequence": self.sequence, "fields": fields})
                self.sequence += 1
            elif op == "JUMP":
                nxt = arg
            elif op == "JUMP_IF_FALSE":
                if not pop():
                    nxt = arg
            elif op == "RETURN":
                if stack:
                    raise VmError("non-empty stack at return")
                return
            else:
                raise VmError("unknown op " + op)
            pc = nxt


def equivalent(a, b):
    """Numbers by value, objects without regard to key order, everything else exactly."""
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool) and a == b
    if is_number(a) and is_number(b):
        return a == b
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(equivalent(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(equivalent(x, y) for x, y in zip(a, b))
    return a == b


def replay(path):
    doc = json.loads(path.read_text(encoding="utf-8"))
    problems, steps = [], 0
    for case in doc["cases"]:
        vm = Vm(doc["program"], case["entityId"], case["behavior"], case["params"], case["seed"])
        if not equivalent(vm.snapshot(), case["initialState"]):
            problems.append(f'{doc["name"]}/{case["name"]}: state after initialization differs')
        for step in case["steps"]:
            steps += 1
            expect = step["expect"]
            try:
                got = vm.step(step["tick"], step["view"], step["events"])
                failed = False
            except VmError:
                got, failed = {"intents": [], "events": [], "state": vm.snapshot()}, True
            same = (failed == expect.get("failure", False) and equivalent(got["state"], expect["state"])
                    and equivalent(got["intents"], expect.get("intents", []))
                    and equivalent(got["events"], expect.get("events", [])))
            if not same:
                problems.append(f'{doc["name"]}/{case["name"]} tick {step["tick"]}: expected {expect} but got {got} (failed={failed})')
    return problems, steps


def replay_prng(path):
    problems = []
    for v in json.loads(path.read_text(encoding="utf-8"))["vectors"]:
        u = draw(v["seed"], v["entityId"], v["behavior"], v["rule"], v["site"], v["counter"])
        if repr(u) != v["unit"] or struct.pack(">d", u).hex() != v["unitBits"]:
            problems.append(f'prng {v["seed"]} {v["entityId"]} {v["site"]!r} {v["counter"]}')
    return problems


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent
    failures = 0
    for path in sorted(root.glob("*.json")):
        if path.name == "prng.json":
            problems, count = replay_prng(path), len(json.loads(path.read_text(encoding="utf-8"))["vectors"])
        else:
            problems, count = replay(path)
        print(f'{path.name:16} {count:5} checks  {"OK" if not problems else str(len(problems)) + " MISMATCHES"}')
        for problem in problems[:5]:
            print("   ", problem[:300])
        failures += len(problems)
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
