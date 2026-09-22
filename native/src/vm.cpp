// The interpreter. Verification has already established that operands are in range and typed correctly, so the
// hot path only checks what depends on values: overflow, division by zero, list bounds, none, and the step limits.
#include "cvm.hpp"
#include "opcodes.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>

namespace cvm {

namespace {

/** Little-endian decoding of the code stream; positions are already known to be valid. */
inline uint64_t readVarint(const uint8_t* code, uint32_t& pc) {
    uint64_t value = 0;
    int shift = 0;
    while (true) {
        uint8_t b = code[pc++];
        value |= static_cast<uint64_t>(b & 0x7F) << shift;
        if ((b & 0x80) == 0) return value;
        shift += 7;
    }
}

inline uint32_t readU32(const uint8_t* code, uint32_t& pc) {
    uint32_t v = static_cast<uint32_t>(code[pc]) | (static_cast<uint32_t>(code[pc + 1]) << 8) |
                 (static_cast<uint32_t>(code[pc + 2]) << 16) | (static_cast<uint32_t>(code[pc + 3]) << 24);
    pc += 4;
    return v;
}

inline double readF64(const uint8_t* code, uint32_t& pc) {
    double value;
    std::memcpy(&value, code + pc, 8);
    pc += 8;
    return value;
}

inline int64_t zigzag(uint64_t v) { return static_cast<int64_t>(v >> 1) ^ -static_cast<int64_t>(v & 1); }

double finite(double value) {
    if (!std::isfinite(value)) throw StepError("result is not a finite number");
    return value;
}

int64_t addExact(int64_t a, int64_t b) {
    int64_t out;
    if (__builtin_add_overflow(a, b, &out)) throw StepError("Int64 overflow");
    return out;
}

int64_t subExact(int64_t a, int64_t b) {
    int64_t out;
    if (__builtin_sub_overflow(a, b, &out)) throw StepError("Int64 overflow");
    return out;
}

int64_t mulExact(int64_t a, int64_t b) {
    int64_t out;
    if (__builtin_mul_overflow(a, b, &out)) throw StepError("Int64 overflow");
    return out;
}

/** Exact mixed comparison: never round the integer to double, and never cast an out-of-range double. */
int compareIntReal(int64_t integer, double real) {
    if (real >= 0x1p63) return -1;
    if (real < -0x1p63) return 1;
    const auto truncated = static_cast<int64_t>(real);
    if (integer != truncated) return integer < truncated ? -1 : 1;
    const double fraction = real - std::trunc(real);
    return fraction > 0 ? -1 : fraction < 0 ? 1 : 0;
}

int numericOrder(const Value& a, const Value& b) {
    if (a.tag == Tag::I64 && b.tag == Tag::F64) return compareIntReal(a.i, b.d);
    if (a.tag == Tag::F64 && b.tag == Tag::I64) return -compareIntReal(b.i, a.d);
    if (a.tag == Tag::I64) return a.i < b.i ? -1 : a.i > b.i ? 1 : 0;
    return a.d < b.d ? -1 : a.d > b.d ? 1 : 0;
}

/** Deep equality, the same rule the reference uses: numbers by value, records and lists element by element. */
bool equalValues(const Value& a, const Value& b) {
    if (a.tag != b.tag) {
        bool numeric = (a.tag == Tag::I64 || a.tag == Tag::F64) && (b.tag == Tag::I64 || b.tag == Tag::F64);
        if (!numeric) return false;
        return numericOrder(a, b) == 0;
    }
    switch (a.tag) {
        case Tag::None: return true;
        case Tag::Bool: return a.b == b.b;
        case Tag::I64: return a.i == b.i;
        case Tag::F64: return a.d == b.d;
        case Tag::Str: return a.str == b.str;
        case Tag::List:
        case Tag::Rec: {
            if (a.tag == Tag::Rec && a.schema != b.schema) return false;
            if (a.block.count != b.block.count) return false;
            for (uint32_t i = 0; i < a.block.count; ++i) {
                if (!equalValues(a.block.items[i], b.block.items[i])) return false;
            }
            return true;
        }
    }
    return false;
}

double asNumber(const Value& value) {
    if (value.tag == Tag::I64) return static_cast<double>(value.i);
    if (value.tag == Tag::F64) return value.d;
    throw StepError("expected a number");
}

bool compare(uint8_t rel, double a, double b) {
    switch (rel) {
        case REL_EQ: return a == b;
        case REL_NE: return a != b;
        case REL_LT: return a < b;
        case REL_LE: return a <= b;
        case REL_GT: return a > b;
        default: return a >= b;
    }
}

bool compareInts(uint8_t rel, int64_t a, int64_t b) {
    switch (rel) {
        case REL_EQ: return a == b;
        case REL_NE: return a != b;
        case REL_LT: return a < b;
        case REL_LE: return a <= b;
        case REL_GT: return a > b;
        default: return a >= b;
    }
}

}  // namespace

struct Vm::Frame {
    uint64_t tick = 0;
    const std::vector<Value>* observations = nullptr;
    const Value* message = nullptr;
    std::vector<Value> locals;
};

Vm::Vm(Program& program, const Behavior& behavior, std::string entityId, int64_t seed,
       std::vector<Value> params, const Limits& limits)
    : program_(program),
      behavior_(behavior),
      entityId_(std::move(entityId)),
      selfId_(program.strings.intern(entityId_)),
      behaviorName_(program.strings.at(behavior.name)),
      seed_(seed),
      limits_(limits),
      params_(std::move(params)),
      arena_(limits.arenaValues) {
    if (params_.size() != behavior_.params.size()) throw LoadError("wrong number of parameters");
    state_.assign(behavior_.state.size(), Value());
    stack_.reserve(behavior_.maxStack);
    budget_ = limits_.instructions;
    Frame frame;
    frame.locals.assign(behavior_.initLocals.size(), Value());
    StepResult discarded;
    run(behavior_.initEntry, nullptr, frame, discarded);
    if (!discarded.intents.empty() || !discarded.events.empty()) throw LoadError("an initializer produced effects");
    arena_.reset();
}

double Vm::draw(std::string_view rule, std::string_view site) {
    std::string key;
    key.reserve(rule.size() + site.size() + 1);
    key.append(rule).push_back('\0');
    key.append(site);
    uint64_t counter = counters_[key]++;
    return randomDraw(seed_, entityId_, behaviorName_, rule, site, counter);
}

StepResult Vm::step(uint64_t tick, const std::vector<Value>& observations, std::vector<DeliveredEvent> events) {
    if (observations.size() != behavior_.observes.size()) throw StepError("frame does not match the observation schema");
    std::vector<Value> stateBefore = state_;
    std::unordered_map<std::string, uint64_t> countersBefore = counters_;
    uint64_t sequenceBefore = sequence_;
    StepResult result;
    budget_ = limits_.instructions;
    arena_.reset();
    try {
        std::stable_sort(events.begin(), events.end(), [this](const DeliveredEvent& a, const DeliveredEvent& b) {
            const std::string& left = program_.strings.at(a.sender);
            const std::string& right = program_.strings.at(b.sender);
            if (left != right) return left < right;
            return a.sequence < b.sequence;
        });
        for (const DeliveredEvent& event : events) {
            for (const Handler& handler : behavior_.handlers) {
                if (handler.timer || handler.trigger != event.eventId) continue;
                Frame frame;
                frame.tick = tick;
                frame.observations = &observations;
                frame.message = &event.payload;
                frame.locals.assign(handler.locals.size(), Value());
                run(handler.entry, &handler, frame, result);
            }
        }
        for (const Handler& handler : behavior_.handlers) {
            if (!handler.timer || tick % handler.trigger != 0) continue;
            Frame frame;
            frame.tick = tick;
            frame.observations = &observations;
            frame.locals.assign(handler.locals.size(), Value());
            run(handler.entry, &handler, frame, result);
        }
    } catch (...) {
        state_ = std::move(stateBefore);
        counters_ = std::move(countersBefore);
        sequence_ = sequenceBefore;
        stack_.clear();
        arena_.reset();
        throw;
    }
    result.instructions = limits_.instructions - budget_;
    return result;
}

void Vm::run(uint32_t entry, const Handler* handler, Frame& frame, StepResult& out) {
    const uint8_t* code = behavior_.code.data();
    const std::string& ruleName = handler != nullptr ? program_.strings.at(handler->name) : behaviorName_;
    uint32_t pc = entry;
    size_t stackBase = stack_.size();

    while (true) {
        if (budget_-- == 0) throw StepError("instruction budget exceeded");
        uint32_t at = pc;
        uint8_t op = code[pc++];
        OpShape shape = shapeOf(op);

        uint8_t rel = 0;
        uint8_t fieldIndex = 0;
        uint64_t immediate = 0;
        uint8_t count = 0;
        if (op == OP_MKREC || op == OP_SEND) { immediate = readVarint(code, pc); count = code[pc++]; }
        if (op == OP_GETF) fieldIndex = code[pc++];
        if (shape.rel) rel = code[pc++];

        uint8_t dstMode = DST_STACK;
        uint32_t dstIndex = 0;
        if (shape.dst || op == OP_MKREC) {
            uint8_t head = code[pc++];
            dstMode = head >> 4;
            dstIndex = head & 15;
            if (dstIndex == kExtendedIndex && dstMode != DST_STACK) dstIndex = static_cast<uint32_t>(readVarint(code, pc));
        }

        uint8_t sources = shape.srcs;
        if (op == OP_MKREC) sources = count;
        if (op == OP_SEND) sources = static_cast<uint8_t>(count + 1);

        Value fixed[4];
        Value* operands = fixed;
        std::vector<Value> spill;
        uint8_t fieldBytes[256];
        if (sources > 4) { spill.resize(sources); operands = spill.data(); }
        uint8_t stackPositions[256];
        uint8_t stackCount = 0;

        for (uint8_t i = 0; i < sources; ++i) {
            if (op == OP_MKREC || (op == OP_SEND && i > 0)) fieldBytes[op == OP_SEND ? i - 1 : i] = code[pc++];
            uint8_t head = code[pc++];
            uint8_t mode = head >> 4;
            uint32_t index = head & 15;
            if (index == kExtendedIndex && mode >= SRC_VIEW && mode <= SRC_VIEW_FIELD) {
                index = static_cast<uint32_t>(readVarint(code, pc));
            }
            switch (mode) {
                case SRC_STACK: stackPositions[stackCount++] = i; break;
                case SRC_IMM_I: operands[i] = Value::integer(zigzag(readVarint(code, pc))); break;
                case SRC_IMM_F: operands[i] = Value::real(readF64(code, pc)); break;
                case SRC_IMM_S: operands[i] = Value::text(static_cast<uint32_t>(readVarint(code, pc))); break;
                case SRC_SMALL:
                    switch (index) {
                        case SMALL_FALSE: operands[i] = Value::boolean(false); break;
                        case SMALL_TRUE: operands[i] = Value::boolean(true); break;
                        case SMALL_NONE: operands[i] = Value::none(); break;
                        case SMALL_I0: operands[i] = Value::integer(0); break;
                        case SMALL_I1: operands[i] = Value::integer(1); break;
                        case SMALL_IM1: operands[i] = Value::integer(-1); break;
                        case SMALL_F0: operands[i] = Value::real(0.0); break;
                        default: operands[i] = Value::real(1.0); break;
                    }
                    break;
                case SRC_VIEW: operands[i] = (*frame.observations)[index]; break;
                case SRC_STATE: operands[i] = state_[index]; break;
                case SRC_LOCAL: operands[i] = frame.locals[index]; break;
                case SRC_PARAM: operands[i] = params_[index]; break;
                case SRC_MSG: operands[i] = frame.message->block.items[index]; break;
                case SRC_LOCAL_FIELD: {
                    uint8_t field = code[pc++];
                    operands[i] = frame.locals[index].block.items[field];
                    break;
                }
                case SRC_VIEW_FIELD: {
                    uint8_t field = code[pc++];
                    operands[i] = (*frame.observations)[index].block.items[field];
                    break;
                }
                default:
                    switch (index) {
                        case SPECIAL_TIME: operands[i] = Value::real(finite(static_cast<double>(frame.tick) * program_.stepSeconds)); break;
                        case SPECIAL_SELF: operands[i] = Value::text(selfId_); break;
                        default: operands[i] = *frame.message; break;
                    }
                    break;
            }
        }
        for (uint8_t i = stackCount; i-- > 0;) {
            if (stack_.size() <= stackBase) throw StepError("operand stack underflow");
            operands[stackPositions[i]] = stack_.back();
            stack_.pop_back();
        }

        uint32_t target = 0;
        if (shape.target) target = readU32(code, pc);

        Value produced;
        bool produces = false;
        bool jump = false;

        switch (op) {
            case OP_MOV: produced = operands[0]; produces = true; break;
            case OP_POP:
                if (stack_.size() <= stackBase) throw StepError("operand stack underflow");
                stack_.pop_back();
                break;
            case OP_ADD_I: produced = Value::integer(addExact(operands[0].i, operands[1].i)); produces = true; break;
            case OP_SUB_I: produced = Value::integer(subExact(operands[0].i, operands[1].i)); produces = true; break;
            case OP_MUL_I: produced = Value::integer(mulExact(operands[0].i, operands[1].i)); produces = true; break;
            case OP_MOD_I:
                if (operands[1].i == 0) throw StepError("modulo by zero");
                if (operands[1].i == -1) { produced = Value::integer(0); produces = true; break; }
                produced = Value::integer(operands[0].i % operands[1].i);
                produces = true;
                break;
            case OP_NEG_I: produced = Value::integer(subExact(0, operands[0].i)); produces = true; break;
            case OP_ADD_F: produced = Value::real(finite(operands[0].d + operands[1].d)); produces = true; break;
            case OP_SUB_F: produced = Value::real(finite(operands[0].d - operands[1].d)); produces = true; break;
            case OP_MUL_F: produced = Value::real(finite(operands[0].d * operands[1].d)); produces = true; break;
            case OP_DIV_F:
                if (operands[1].d == 0.0) throw StepError("division by zero");
                produced = Value::real(finite(operands[0].d / operands[1].d));
                produces = true;
                break;
            case OP_NEG_F: produced = Value::real(finite(-operands[0].d)); produces = true; break;
            case OP_TOF: produced = Value::real(finite(asNumber(operands[0]))); produces = true; break;
            case OP_TOF_OPT:
                produced = operands[0].isNone() ? Value::none() : Value::real(finite(asNumber(operands[0])));
                produces = true;
                break;
            case OP_CLAMP_I: {
                if (operands[1].i > operands[2].i) throw StepError("clamp bounds are crossed");
                int64_t x = operands[0].i;
                produced = Value::integer(x < operands[1].i ? operands[1].i : (x > operands[2].i ? operands[2].i : x));
                produces = true;
                break;
            }
            case OP_CLAMP_F: {
                if (operands[1].d > operands[2].d) throw StepError("clamp bounds are crossed");
                double x = operands[0].d;
                produced = Value::real(x < operands[1].d ? operands[1].d : (x > operands[2].d ? operands[2].d : x));
                produces = true;
                break;
            }
            case OP_CMP_I: produced = Value::boolean(compareInts(rel, operands[0].i, operands[1].i)); produces = true; break;
            case OP_CMP_F: produced = Value::boolean(compare(rel, operands[0].d, operands[1].d)); produces = true; break;
            case OP_CMP_N: produced = Value::boolean(compareInts(rel, numericOrder(operands[0], operands[1]), 0)); produces = true; break;
            case OP_EQ_S: produced = Value::boolean((operands[0].str == operands[1].str) == (rel == REL_EQ)); produces = true; break;
            case OP_EQ_B: produced = Value::boolean((operands[0].b == operands[1].b) == (rel == REL_EQ)); produces = true; break;
            case OP_EQ_A: produced = Value::boolean(equalValues(operands[0], operands[1]) == (rel == REL_EQ)); produces = true; break;
            case OP_NOT: produced = Value::boolean(!operands[0].b); produces = true; break;
            case OP_JMP: jump = true; break;
            case OP_BR_T: jump = operands[0].b; break;
            case OP_BR_F: jump = !operands[0].b; break;
            case OP_BR_CMP_I: jump = compareInts(rel, operands[0].i, operands[1].i); break;
            case OP_BR_CMP_F: jump = compare(rel, operands[0].d, operands[1].d); break;
            case OP_BR_CMP_N: jump = compareInts(rel, numericOrder(operands[0], operands[1]), 0); break;
            case OP_BR_EQ_S: jump = (operands[0].str == operands[1].str) == (rel == REL_EQ); break;
            case OP_BR_EQ_B: jump = (operands[0].b == operands[1].b) == (rel == REL_EQ); break;
            case OP_BR_EQ_A: jump = equalValues(operands[0], operands[1]) == (rel == REL_EQ); break;
            case OP_BR_SOME: jump = !operands[0].isNone(); break;
            case OP_BR_NONE: jump = operands[0].isNone(); break;
            case OP_LET_SOME:
                if (operands[0].isNone()) { jump = true; break; }
                produced = operands[0];
                produces = true;
                break;
            case OP_IS_SOME: produced = Value::boolean(!operands[0].isNone()); produces = true; break;
            case OP_UNWRAP:
                if (operands[0].isNone()) throw StepError("unwrap of none");
                produced = operands[0];
                produces = true;
                break;
            case OP_SOME: produced = operands[0]; produces = true; break;
            case OP_GETF: produced = operands[0].block.items[fieldIndex]; produces = true; break;
            case OP_INDEX: {
                int64_t index = operands[1].i;
                if (index < 0 || static_cast<uint64_t>(index) >= operands[0].block.count) throw StepError("index out of range");
                produced = operands[0].block.items[index];
                produces = true;
                break;
            }
            case OP_NEAREST: {
                const Block& list = operands[0].block;
                if (list.count > 4096) throw StepError("observation list is too long");
                const Value* best = nullptr;
                double bestDistance = 0.0;
                for (uint32_t i = 0; i < list.count; ++i) {
                    const Value& candidate = list.items[i];
                    const Schema& schema = program_.schemas.at(candidate.schema);
                    if (schema.idField < 0 || schema.distanceField < 0) throw StepError("nearest needs targets with id and distance");
                    double distance = asNumber(candidate.block.items[schema.distanceField]);
                    if (best == nullptr || distance < bestDistance ||
                        (distance == bestDistance &&
                         program_.strings.at(candidate.block.items[schema.idField].str) <
                             program_.strings.at(best->block.items[program_.schemas.at(best->schema).idField].str))) {
                        best = &candidate;
                        bestDistance = distance;
                    }
                }
                produced = best == nullptr ? Value::none() : *best;
                produces = true;
                break;
            }
            case OP_MKREC: {
                Value* items = arena_.allocate(count);
                for (uint8_t i = 0; i < count; ++i) items[fieldBytes[i]] = operands[i];
                produced = Value::record(static_cast<uint32_t>(immediate), count, items);
                produces = true;
                break;
            }
            case OP_CHANCE: case OP_HAZARD: {
                double parameter = operands[0].d;
                double probability = parameter;
                if (op == OP_HAZARD) {
                    if (parameter < 0.0) throw StepError("rate must not be negative");
                    probability = -std::expm1(-parameter * program_.stepSeconds);
                }
                if (!(probability >= 0.0 && probability <= 1.0)) throw StepError("probability outside [0,1]");
                produced = Value::boolean(draw(ruleName, program_.strings.at(operands[1].str)) < probability);
                produces = true;
                break;
            }
            case OP_POWER: case OP_REPAIR: case OP_MOTION: case OP_DAMAGE: {
                if (out.intents.size() + out.events.size() >= limits_.outputs) throw StepError("too many outputs in one step");
                Intent intent;
                intent.operation = op;
                intent.arguments.assign(operands, operands + sources);
                out.intents.push_back(std::move(intent));
                break;
            }
            case OP_SEND: {
                if (out.intents.size() + out.events.size() >= limits_.outputs) throw StepError("too many outputs in one step");
                const EventDef* event = program_.event(immediate);
                const Schema& schema = program_.schemas.at(event->schema);
                Value* items = arena_.allocate(schema.fields.size());
                for (uint8_t i = 1; i < sources; ++i) items[fieldBytes[i - 1]] = operands[i];
                OutgoingEvent outgoing;
                outgoing.target = operands[0].str;
                outgoing.eventId = immediate;
                outgoing.sequence = sequence_++;
                outgoing.payload = Value::record(event->schema, static_cast<uint32_t>(schema.fields.size()), items);
                out.events.push_back(std::move(outgoing));
                break;
            }
            case OP_RET:
                if (stack_.size() != stackBase) throw StepError("stack is not empty at the end of a handler");
                return;
            default:
                throw StepError(std::string("unknown opcode at ") + std::to_string(at));
        }

        if (produces) {
            if (dstMode == DST_STACK) {
                if (stack_.size() >= limits_.stack) throw StepError("operand stack limit exceeded");
                stack_.push_back(produced);
            } else if (dstMode == DST_STATE) {
                state_[dstIndex] = produced;
            } else {
                frame.locals[dstIndex] = produced;
            }
        }
        if (jump) pc = target;
    }
}

}  // namespace cvm
