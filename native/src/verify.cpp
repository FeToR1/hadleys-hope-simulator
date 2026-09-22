// Verification of a behavior's code: every path is walked once and checked for operand ranges, types,
// stack heights, definite assignment of locals, jump targets and capabilities (docs/cvm-v2.md, section 6).
// A program that passes cannot make the interpreter read out of bounds or work on a value of the wrong type.
#include "cvm.hpp"
#include "opcodes.hpp"

#include <deque>
#include <optional>
#include <set>
#include <sstream>

namespace cvm {
namespace {

TypeRef plain(Tag tag) { TypeRef t; t.tag = tag; return t; }
TypeRef optionalOf(TypeRef t) { t.optional = true; return t; }

/** The type of a value on the abstract stack; unknown stands for the `none` literal, which fits any Option. */
struct Abstract {
    TypeRef type;
    bool none = false;
    /** The operand takes its value from the stack, so its type is only known at this point in the walk. */
    bool fromStack = false;
};

Abstract typed(TypeRef t) { return Abstract{t, false}; }
Abstract noneValue() { return Abstract{TypeRef{}, true}; }

bool assignable(const Abstract& value, const TypeRef& slot) {
    if (value.none) return slot.optional;
    if (value.type.optional && !slot.optional) return false;
    TypeRef bare = value.type;
    bare.optional = slot.optional;
    return sameType(bare, slot);
}

/** State of the abstract interpretation at one instruction. */
struct Frame {
    std::vector<Abstract> stack;
    std::vector<bool> defined;
};

bool sameFrame(const Frame& a, const Frame& b) {
    if (a.stack.size() != b.stack.size()) return false;
    for (size_t i = 0; i < a.stack.size(); ++i) {
        if (a.stack[i].none != b.stack[i].none) return false;
        if (!a.stack[i].none && !sameType(a.stack[i].type, b.stack[i].type)) return false;
    }
    return true;
}

class Verifier {
public:
    Verifier(const Program& program, const Behavior& behavior) : program_(program), behavior_(behavior) {}

    void run() {
        // A slot that outlives the step holds only a flat value; a record or a list would point into the
        // step arena, which is reset, so this is a safety rule and not only a rule of the language.
        for (const Slot& slot : behavior_.state) {
            if (slot.type.tag == Tag::Rec || slot.type.tag == Tag::List) fail("state may not hold a record or a list");
        }
        for (const Slot& slot : behavior_.params) {
            // A parameter lives for the whole run, so it may not point into the arena the host resets per frame.
            if (slot.type.tag == Tag::List || slot.type.tag == Tag::Rec) fail("a parameter may not be a record or a list");
        }
        scanInstructions();
        checkEntry(behavior_.initEntry, behavior_.initLocals, nullptr, /*initializer=*/true);
        for (const Handler& handler : behavior_.handlers) {
            if (!handler.timer && program_.event(handler.trigger) == nullptr) fail("handler subscribes to an unknown event");
            checkEntry(handler.entry, handler.locals, &handler, /*initializer=*/false);
        }
    }

private:
    [[noreturn]] void fail(const std::string& message) const {
        throw LoadError("behavior " + program_.strings.at(behavior_.name) + ": " + message);
    }

    [[noreturn]] void failAt(uint32_t offset, const std::string& message) const {
        std::ostringstream out;
        out << "behavior " << program_.strings.at(behavior_.name) << " at offset " << offset << ": " << message;
        throw LoadError(out.str());
    }

    /** Positions where an instruction begins; a jump may only target one of them. */
    void scanInstructions() {
        uint32_t offset = 0;
        const std::vector<uint8_t>& code = behavior_.code;
        if (code.empty()) fail("code is empty");
        if (behavior_.maxStack > 4096) fail("declared stack is too large");
        while (offset < code.size()) {
            boundaries_.insert(offset);
            offset = skipInstruction(offset);
        }
        if (offset != code.size()) fail("the last instruction runs past the end of the code");
    }

    uint8_t byteAt(uint32_t offset) const {
        if (offset >= behavior_.code.size()) failAt(offset, "instruction runs past the end of the code");
        return behavior_.code[offset];
    }

    uint64_t varintAt(uint32_t& offset) const {
        uint64_t value = 0;
        int shift = 0;
        while (true) {
            uint8_t b = byteAt(offset++);
            value |= static_cast<uint64_t>(b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift >= 64) failAt(offset, "varint is too long");
        }
    }

    /** Skips one source operand and returns its mode and index. */
    std::pair<uint8_t, uint64_t> skipSource(uint32_t& offset) const {
        uint8_t head = byteAt(offset++);
        uint8_t mode = head >> 4;
        uint64_t index = head & 15;
        if (index == kExtendedIndex && mode >= SRC_VIEW && mode <= SRC_VIEW_FIELD) index = varintAt(offset);
        switch (mode) {
            case SRC_IMM_I: varintAt(offset); break;
            case SRC_IMM_F: offset += 8; byteAt(offset - 1); break;
            case SRC_IMM_S: index = varintAt(offset); break;
            case SRC_LOCAL_FIELD: case SRC_VIEW_FIELD: byteAt(offset++); break;
            default: break;
        }
        return {mode, index};
    }

    std::pair<uint8_t, uint64_t> skipDestination(uint32_t& offset) const {
        uint8_t head = byteAt(offset++);
        uint8_t mode = head >> 4;
        uint64_t index = head & 15;
        if (index == kExtendedIndex && mode != DST_STACK) index = varintAt(offset);
        return {mode, index};
    }

    uint32_t skipInstruction(uint32_t offset) const {
        uint8_t op = byteAt(offset++);
        OpShape shape = shapeOf(op);
        if (!shape.known) failAt(offset - 1, "unknown opcode");
        if (op == OP_MKREC) {
            varintAt(offset);
            uint8_t count = byteAt(offset++);
            skipDestination(offset);
            for (uint8_t i = 0; i < count; ++i) { byteAt(offset++); skipSource(offset); }
            return offset;
        }
        if (op == OP_SEND) {
            varintAt(offset);
            uint8_t count = byteAt(offset++);
            skipSource(offset);
            for (uint8_t i = 0; i < count; ++i) { byteAt(offset++); skipSource(offset); }
            return offset;
        }
        if (op == OP_GETF) { byteAt(offset++); skipDestination(offset); skipSource(offset); return offset; }
        if (shape.rel) byteAt(offset++);
        if (shape.dst) skipDestination(offset);
        for (uint8_t i = 0; i < shape.srcs; ++i) skipSource(offset);
        if (shape.target) { offset += 4; byteAt(offset - 1); }
        return offset;
    }

    void checkEntry(uint32_t entry, const std::vector<TypeRef>& locals, const Handler* handler, bool initializer);

    /** Reads one source operand and returns the type it produces. */
    Abstract source(uint32_t& offset, const std::vector<TypeRef>& locals, const Frame& frame,
                    const Handler* handler, bool initializer);

    const Program& program_;
    const Behavior& behavior_;
    std::set<uint32_t> boundaries_;
};

void Verifier::checkEntry(uint32_t entry, const std::vector<TypeRef>& locals, const Handler* handler, bool initializer) {
    if (!boundaries_.count(entry)) fail("entry point is not an instruction boundary");
    std::vector<std::optional<Frame>> visited(behavior_.code.size());
    std::deque<std::pair<uint32_t, Frame>> work;
    Frame start;
    start.defined.assign(locals.size(), false);
    work.emplace_back(entry, start);

    while (!work.empty()) {
        auto [offset, frame] = work.front();
        work.pop_front();
        if (!boundaries_.count(offset)) failAt(offset, "control flow reaches the middle of an instruction");
        if (visited[offset]) {
            if (!sameFrame(*visited[offset], frame)) failAt(offset, "paths meet with different stack contents");
            // Definite assignment is the intersection of the paths that reach here.
            bool narrowed = false;
            Frame merged = *visited[offset];
            for (size_t i = 0; i < merged.defined.size(); ++i) {
                bool both = merged.defined[i] && frame.defined[i];
                if (both != merged.defined[i]) { merged.defined[i] = both; narrowed = true; }
            }
            if (!narrowed) continue;
            visited[offset] = merged;
            frame = merged;
        } else {
            visited[offset] = frame;
        }

        uint32_t pc = offset;
        uint8_t op = byteAt(pc++);
        OpShape shape = shapeOf(op);
        uint8_t rel = 0;
        uint8_t field = 0;
        uint64_t immediate = 0;
        uint8_t count = 0;
        if (op == OP_MKREC || op == OP_SEND) { immediate = varintAt(pc); count = byteAt(pc++); }
        if (op == OP_GETF) field = byteAt(pc++);
        if (shape.rel) rel = byteAt(pc++);
        if (rel > REL_GE) failAt(offset, "unknown relation");

        // The destination of a variadic instruction comes before its operands; for SEND there is none.
        uint8_t dstMode = DST_STACK;
        uint64_t dstIndex = 0;
        if (shape.dst || op == OP_MKREC) {
            auto destination = skipDestination(pc);
            dstMode = destination.first;
            dstIndex = destination.second;
            if (dstMode == DST_STATE && dstIndex >= behavior_.state.size()) failAt(offset, "state slot out of range");
            if (dstMode == DST_LOCAL && dstIndex >= locals.size()) failAt(offset, "local slot out of range");
            if (dstMode > DST_LOCAL) failAt(offset, "unknown destination mode");
        }

        std::vector<Abstract> operands;
        uint8_t sources = shape.srcs;
        if (op == OP_MKREC) sources = count;
        if (op == OP_SEND) sources = static_cast<uint8_t>(count + 1);
        std::vector<uint8_t> fields;
        std::vector<size_t> fromStack;
        for (uint8_t i = 0; i < sources; ++i) {
            if (op == OP_MKREC || (op == OP_SEND && i > 0)) fields.push_back(byteAt(pc++));
            operands.push_back(source(pc, locals, frame, handler, initializer));
            if (operands.back().fromStack) fromStack.push_back(operands.size() - 1);
        }
        // Stack operands are taken right to left, exactly as the interpreter pops them.
        if (fromStack.size() > frame.stack.size()) failAt(offset, "stack underflow");
        for (size_t i = fromStack.size(); i-- > 0;) {
            operands[fromStack[i]] = frame.stack.back();
            frame.stack.pop_back();
        }
        uint32_t target = 0;
        if (shape.target) {
            target = static_cast<uint32_t>(byteAt(pc)) | (static_cast<uint32_t>(byteAt(pc + 1)) << 8) |
                     (static_cast<uint32_t>(byteAt(pc + 2)) << 16) | (static_cast<uint32_t>(byteAt(pc + 3)) << 24);
            pc += 4;
            if (!boundaries_.count(target)) failAt(offset, "jump target is not an instruction boundary");
        }

        auto operandType = [&](size_t i) -> const Abstract& { return operands[i]; };
        auto requireTag = [&](size_t i, Tag tag) {
            const Abstract& value = operandType(i);
            if (value.none || value.type.optional || value.type.tag != tag) failAt(offset, "operand has the wrong type");
        };
        auto requireNumber = [&](size_t i) {
            const Abstract& value = operandType(i);
            if (value.none || value.type.optional || (value.type.tag != Tag::I64 && value.type.tag != Tag::F64)) {
                failAt(offset, "operand is not a number");
            }
        };

        std::optional<Abstract> produced;
        switch (op) {
            case OP_MOV: produced = operandType(0); break;
            case OP_POP: break;
            case OP_ADD_I: case OP_SUB_I: case OP_MUL_I: case OP_MOD_I:
                requireTag(0, Tag::I64); requireTag(1, Tag::I64); produced = typed(plain(Tag::I64)); break;
            case OP_NEG_I: requireTag(0, Tag::I64); produced = typed(plain(Tag::I64)); break;
            case OP_ADD_F: case OP_SUB_F: case OP_MUL_F: case OP_DIV_F:
                requireTag(0, Tag::F64); requireTag(1, Tag::F64); produced = typed(plain(Tag::F64)); break;
            case OP_NEG_F: requireTag(0, Tag::F64); produced = typed(plain(Tag::F64)); break;
            case OP_TOF: requireNumber(0); produced = typed(plain(Tag::F64)); break;
            case OP_TOF_OPT: {
                const Abstract& value = operandType(0);
                if (!value.none && !(value.type.optional && (value.type.tag == Tag::I64 || value.type.tag == Tag::F64))) {
                    failAt(offset, "TOF_OPT needs an optional number");
                }
                produced = typed(optionalOf(plain(Tag::F64)));
                break;
            }
            case OP_CLAMP_I:
                requireTag(0, Tag::I64); requireTag(1, Tag::I64); requireTag(2, Tag::I64);
                produced = typed(plain(Tag::I64)); break;
            case OP_CLAMP_F:
                requireTag(0, Tag::F64); requireTag(1, Tag::F64); requireTag(2, Tag::F64);
                produced = typed(plain(Tag::F64)); break;
            // The comparisons produce a value; the BR_ forms only decide the jump, so they produce nothing.
            case OP_CMP_I: requireTag(0, Tag::I64); requireTag(1, Tag::I64); produced = typed(plain(Tag::Bool)); break;
            case OP_CMP_F: requireTag(0, Tag::F64); requireTag(1, Tag::F64); produced = typed(plain(Tag::Bool)); break;
            case OP_CMP_N: requireNumber(0); requireNumber(1); produced = typed(plain(Tag::Bool)); break;
            case OP_EQ_S: requireTag(0, Tag::Str); requireTag(1, Tag::Str); produced = typed(plain(Tag::Bool)); break;
            case OP_EQ_B: requireTag(0, Tag::Bool); requireTag(1, Tag::Bool); produced = typed(plain(Tag::Bool)); break;
            case OP_EQ_A:
                if (rel > REL_NE) failAt(offset, "only equality is defined for these operands");
                produced = typed(plain(Tag::Bool)); break;
            case OP_BR_CMP_I: requireTag(0, Tag::I64); requireTag(1, Tag::I64); break;
            case OP_BR_CMP_F: requireTag(0, Tag::F64); requireTag(1, Tag::F64); break;
            case OP_BR_CMP_N: requireNumber(0); requireNumber(1); break;
            case OP_BR_EQ_S: requireTag(0, Tag::Str); requireTag(1, Tag::Str); break;
            case OP_BR_EQ_B: requireTag(0, Tag::Bool); requireTag(1, Tag::Bool); break;
            case OP_BR_EQ_A:
                if (rel > REL_NE) failAt(offset, "only equality is defined for these operands");
                break;
            case OP_NOT: requireTag(0, Tag::Bool); produced = typed(plain(Tag::Bool)); break;
            case OP_BR_T: case OP_BR_F: requireTag(0, Tag::Bool); break;
            case OP_IS_SOME: {
                const Abstract& value = operandType(0);
                if (!value.none && !value.type.optional) failAt(offset, "IS_SOME needs an optional value");
                produced = typed(plain(Tag::Bool));
                break;
            }
            case OP_SOME: {
                Abstract value = operandType(0);
                if (value.none) { produced = noneValue(); break; }
                if (value.type.optional) failAt(offset, "some of an optional value");
                produced = typed(optionalOf(value.type));
                break;
            }
            case OP_UNWRAP: {
                Abstract value = operandType(0);
                if (value.none) failAt(offset, "unwrap of the none literal");
                if (!value.type.optional) failAt(offset, "unwrap of a value that is not optional");
                TypeRef inner = value.type;
                inner.optional = false;
                produced = typed(inner);
                break;
            }
            case OP_BR_SOME: case OP_BR_NONE: break;
            case OP_LET_SOME: {
                Abstract value = operandType(0);
                if (!value.none && !value.type.optional) failAt(offset, "LET_SOME needs an optional value");
                TypeRef inner = value.type;
                inner.optional = false;
                produced = typed(inner);
                break;
            }
            case OP_GETF: {
                const Abstract& value = operandType(0);
                if (value.none || value.type.optional || value.type.tag != Tag::Rec) failAt(offset, "field of a value that is not a record");
                const Schema& schema = program_.schemas.at(value.type.schema);
                if (field >= schema.fields.size()) failAt(offset, "field index out of range");
                produced = typed(schema.fields[field].type);
                break;
            }
            case OP_INDEX: {
                const Abstract& value = operandType(0);
                if (value.none || value.type.tag != Tag::List || !value.type.element) failAt(offset, "index of a value that is not a list");
                requireTag(1, Tag::I64);
                produced = typed(*value.type.element);
                break;
            }
            case OP_NEAREST: {
                const Abstract& value = operandType(0);
                if (value.none || value.type.tag != Tag::List || !value.type.element || value.type.element->tag != Tag::Rec) {
                    failAt(offset, "nearest needs a list of records");
                }
                produced = typed(optionalOf(*value.type.element));
                break;
            }
            case OP_MKREC: {
                if (immediate >= program_.schemas.size()) failAt(offset, "record schema out of range");
                const Schema& schema = program_.schemas.at(immediate);
                if (count != schema.fields.size()) failAt(offset, "record is missing fields");
                std::set<uint8_t> seen;
                for (size_t i = 0; i < operands.size(); ++i) {
                    uint8_t index = fields[i];
                    if (index >= schema.fields.size() || !seen.insert(index).second) failAt(offset, "bad record field");
                    if (!assignable(operands[i], schema.fields[index].type)) failAt(offset, "record field has the wrong type");
                }
                TypeRef type = plain(Tag::Rec);
                type.schema = static_cast<uint32_t>(immediate);
                produced = typed(type);
                break;
            }
            case OP_CHANCE: case OP_HAZARD:
                requireTag(0, Tag::F64); requireTag(1, Tag::Str); produced = typed(plain(Tag::Bool)); break;
            case OP_POWER:
                if (!(behavior_.capabilities & CAP_POWER)) failAt(offset, "behavior has no power capability");
                requireTag(0, Tag::F64); break;
            case OP_MOTION:
                if (!(behavior_.capabilities & CAP_MOTION)) failAt(offset, "behavior has no motion capability");
                requireTag(0, Tag::Rec); requireTag(1, Tag::F64); break;
            case OP_DAMAGE:
                if (!(behavior_.capabilities & CAP_DAMAGE)) failAt(offset, "behavior has no damage capability");
                requireTag(0, Tag::Str); requireTag(1, Tag::F64); requireTag(2, Tag::Str); break;
            case OP_REPAIR:
                if (!(behavior_.capabilities & CAP_REPAIR)) failAt(offset, "behavior has no repair capability");
                requireTag(0, Tag::Str); break;
            case OP_SEND: {
                const EventDef* event = program_.event(immediate);
                if (event == nullptr) failAt(offset, "send of an unknown event");
                requireTag(0, Tag::Str);
                const Schema& schema = program_.schemas.at(event->schema);
                if (count != schema.fields.size()) failAt(offset, "event is missing fields");
                std::set<uint8_t> seen;
                for (size_t i = 1; i < operands.size(); ++i) {
                    uint8_t index = fields[i - 1];
                    if (index >= schema.fields.size() || !seen.insert(index).second) failAt(offset, "bad event field");
                    if (!assignable(operands[i], schema.fields[index].type)) failAt(offset, "event field has the wrong type");
                }
                break;
            }
            case OP_JMP: case OP_RET: break;
            default: failAt(offset, "unknown opcode");
        }

        if (initializer && (op == OP_CHANCE || op == OP_HAZARD || op == OP_POWER || op == OP_MOTION ||
                            op == OP_DAMAGE || op == OP_REPAIR || op == OP_SEND)) {
            failAt(offset, "an initializer cannot use randomness or effects");
        }

        Frame next = frame;
        if (produced) {
            if (dstMode == DST_STACK) {
                next.stack.push_back(*produced);
                if (next.stack.size() > behavior_.maxStack) failAt(offset, "stack grows past the declared maximum");
            } else if (dstMode == DST_STATE) {
                if (!assignable(*produced, behavior_.state.at(dstIndex).type)) failAt(offset, "value does not fit the state slot");
            } else {
                if (!assignable(*produced, locals.at(dstIndex))) failAt(offset, "value does not fit the local slot");
                next.defined.at(dstIndex) = true;
            }
        }
        if (op == OP_POP) {
            if (next.stack.empty()) failAt(offset, "stack underflow");
            next.stack.pop_back();
        }

        if (op == OP_RET) {
            if (!next.stack.empty()) failAt(offset, "stack is not empty at RET");
            continue;
        }
        if (op == OP_LET_SOME) {
            // The value is bound only when it is there; the branch is taken when it is none.
            Frame taken = frame;
            taken.defined = frame.defined;
            work.emplace_back(target, taken);
            work.emplace_back(pc, next);
            continue;
        }
        if (shape.target) {
            work.emplace_back(target, next);
            if (op != OP_JMP) work.emplace_back(pc, next);
            continue;
        }
        if (pc >= behavior_.code.size()) failAt(offset, "control flow runs past the end of the code");
        work.emplace_back(pc, next);
    }
}

Abstract Verifier::source(uint32_t& offset, const std::vector<TypeRef>& locals, const Frame& frame,
                          const Handler* handler, bool initializer) {
    uint32_t at = offset;
    uint8_t head = byteAt(offset++);
    uint8_t mode = head >> 4;
    uint64_t index = head & 15;
    if (index == kExtendedIndex && mode >= SRC_VIEW && mode <= SRC_VIEW_FIELD) index = varintAt(offset);
    switch (mode) {
        case SRC_STACK: { Abstract value; value.fromStack = true; return value; }
        case SRC_IMM_I: varintAt(offset); return typed(plain(Tag::I64));
        case SRC_IMM_F: offset += 8; byteAt(offset - 1); return typed(plain(Tag::F64));
        case SRC_IMM_S: {
            uint64_t id = varintAt(offset);
            if (id >= program_.strings.size()) failAt(at, "string index out of range");
            return typed(plain(Tag::Str));
        }
        case SRC_SMALL:
            switch (index) {
                case SMALL_FALSE: case SMALL_TRUE: return typed(plain(Tag::Bool));
                case SMALL_NONE: return noneValue();
                case SMALL_I0: case SMALL_I1: case SMALL_IM1: return typed(plain(Tag::I64));
                case SMALL_F0: case SMALL_F1: return typed(plain(Tag::F64));
                default: failAt(at, "unknown small constant");
            }
        case SRC_VIEW:
            if (initializer) failAt(at, "an initializer cannot read observations");
            if (index >= behavior_.observes.size()) failAt(at, "observation index out of range");
            return typed(behavior_.observes[index].type);
        case SRC_STATE:
            if (index >= behavior_.state.size()) failAt(at, "state slot out of range");
            return typed(behavior_.state[index].type);
        case SRC_LOCAL:
            if (index >= locals.size()) failAt(at, "local slot out of range");
            if (!frame.defined[index]) failAt(at, "local is read before it is written");
            return typed(locals[index]);
        case SRC_PARAM:
            if (index >= behavior_.params.size()) failAt(at, "parameter index out of range");
            return typed(behavior_.params[index].type);
        case SRC_MSG: {
            if (handler == nullptr || handler->timer) failAt(at, "only an event handler has a message");
            const EventDef* event = program_.event(handler->trigger);
            const Schema& schema = program_.schemas.at(event->schema);
            if (index >= schema.fields.size()) failAt(at, "message field out of range");
            return typed(schema.fields[index].type);
        }
        case SRC_LOCAL_FIELD: {
            if (index >= locals.size()) failAt(at, "local slot out of range");
            if (!frame.defined[index]) failAt(at, "local is read before it is written");
            uint8_t field = byteAt(offset++);
            const TypeRef& type = locals[index];
            if (type.optional || type.tag != Tag::Rec) failAt(at, "field of a local that is not a record");
            const Schema& schema = program_.schemas.at(type.schema);
            if (field >= schema.fields.size()) failAt(at, "field index out of range");
            return typed(schema.fields[field].type);
        }
        case SRC_VIEW_FIELD: {
            if (initializer) failAt(at, "an initializer cannot read observations");
            if (index >= behavior_.observes.size()) failAt(at, "observation index out of range");
            uint8_t field = byteAt(offset++);
            const TypeRef& type = behavior_.observes[index].type;
            if (type.optional || type.tag != Tag::Rec) failAt(at, "field of an observation that is not a record");
            const Schema& schema = program_.schemas.at(type.schema);
            if (field >= schema.fields.size()) failAt(at, "field index out of range");
            return typed(schema.fields[field].type);
        }
        case SRC_SPECIAL:
            switch (index) {
                case SPECIAL_TIME:
                    if (initializer) failAt(at, "an initializer cannot read the clock");
                    return typed(plain(Tag::F64));
                case SPECIAL_SELF: return typed(plain(Tag::Str));
                case SPECIAL_MSG: {
                    if (handler == nullptr || handler->timer) failAt(at, "only an event handler has a message");
                    const EventDef* event = program_.event(handler->trigger);
                    TypeRef type = plain(Tag::Rec);
                    type.schema = event->schema;
                    return typed(type);
                }
                default: failAt(at, "unknown special operand");
            }
        default: failAt(at, "unknown operand mode");
    }
}

}  // namespace

void verifyBehavior(const Program& program, const Behavior& behavior) {
    Verifier(program, behavior).run();
}

}  // namespace cvm
