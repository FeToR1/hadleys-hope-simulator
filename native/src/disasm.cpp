// Instruction listing for --disasm and for error messages: the same text the Kotlin disassembler prints.
#include "cvm.hpp"
#include "opcodes.hpp"

#include <cstring>
#include <sstream>

namespace cvm {
namespace {

const char* relName(uint8_t rel) {
    switch (rel) {
        case REL_EQ: return "EQ"; case REL_NE: return "NE"; case REL_LT: return "LT";
        case REL_LE: return "LE"; case REL_GT: return "GT"; default: return "GE";
    }
}

struct Cursor {
    const std::vector<uint8_t>& code;
    uint32_t pc = 0;
    uint8_t u8() { return code[pc++]; }
    uint64_t uvarint() {
        uint64_t v = 0;
        int shift = 0;
        while (true) {
            uint8_t b = u8();
            v |= static_cast<uint64_t>(b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
        }
    }
    uint32_t u32() {
        uint32_t v = static_cast<uint32_t>(code[pc]) | (static_cast<uint32_t>(code[pc + 1]) << 8) |
                     (static_cast<uint32_t>(code[pc + 2]) << 16) | (static_cast<uint32_t>(code[pc + 3]) << 24);
        pc += 4;
        return v;
    }
    double f64() { double v; std::memcpy(&v, code.data() + pc, 8); pc += 8; return v; }
};

std::string number(double value) {
    std::ostringstream out;
    out << value;
    return out.str();
}

std::string source(Cursor& in, const Program& program) {
    uint8_t head = in.u8();
    uint8_t mode = head >> 4;
    uint64_t index = head & 15;
    if (index == kExtendedIndex && mode >= SRC_VIEW && mode <= SRC_VIEW_FIELD) index = in.uvarint();
    std::ostringstream out;
    switch (mode) {
        case SRC_STACK: out << "stack"; break;
        case SRC_IMM_I: {
            uint64_t raw = in.uvarint();
            out << "#" << (static_cast<int64_t>(raw >> 1) ^ -static_cast<int64_t>(raw & 1));
            break;
        }
        case SRC_IMM_F: out << "#" << number(in.f64()); break;
        case SRC_IMM_S: out << "\"" << program.strings.at(static_cast<uint32_t>(in.uvarint())) << "\""; break;
        case SRC_SMALL:
            switch (index) {
                case SMALL_FALSE: out << "#false"; break;
                case SMALL_TRUE: out << "#true"; break;
                case SMALL_NONE: out << "#none"; break;
                case SMALL_I0: out << "#0"; break;
                case SMALL_I1: out << "#1"; break;
                case SMALL_IM1: out << "#-1"; break;
                case SMALL_F0: out << "#0.0"; break;
                default: out << "#1.0"; break;
            }
            break;
        case SRC_VIEW: out << "view[" << index << "]"; break;
        case SRC_STATE: out << "state[" << index << "]"; break;
        case SRC_LOCAL: out << "local[" << index << "]"; break;
        case SRC_PARAM: out << "param[" << index << "]"; break;
        case SRC_MSG: out << "msg[" << index << "]"; break;
        case SRC_LOCAL_FIELD: out << "local[" << index << "]." << static_cast<int>(in.u8()); break;
        case SRC_VIEW_FIELD: out << "view[" << index << "]." << static_cast<int>(in.u8()); break;
        default:
            switch (index) {
                case SPECIAL_TIME: out << "time"; break;
                case SPECIAL_SELF: out << "self"; break;
                default: out << "msg"; break;
            }
            break;
    }
    return out.str();
}

std::string destination(Cursor& in) {
    uint8_t head = in.u8();
    uint8_t mode = head >> 4;
    uint64_t index = head & 15;
    if (index == kExtendedIndex && mode != DST_STACK) index = in.uvarint();
    std::ostringstream out;
    if (mode == DST_STACK) out << "stack<-";
    else if (mode == DST_STATE) out << "state[" << index << "]<-";
    else out << "local[" << index << "]<-";
    return out.str();
}

}  // namespace

std::vector<std::string> disassemble(const Program& program, const Behavior& behavior) {
    std::vector<std::string> lines;
    lines.push_back("behavior " + program.strings.at(behavior.name) + " for " + program.strings.at(behavior.kind) +
                    " (" + std::to_string(behavior.code.size()) + " bytes, stack " + std::to_string(behavior.maxStack) + ")");
    {
        std::ostringstream head;
        head << "  init @" << behavior.initEntry;
        for (const Handler& handler : behavior.handlers) {
            head << "; " << program.strings.at(handler.name) << " @" << handler.entry
                 << (handler.timer ? " every " : " on event ") << handler.trigger;
        }
        lines.push_back(head.str());
    }

    Cursor in{behavior.code};
    while (in.pc < behavior.code.size()) {
        uint32_t at = in.pc;
        uint8_t op = in.u8();
        OpShape shape = shapeOf(op);
        std::ostringstream out;
        out << std::hex;
        char offset[16];
        std::snprintf(offset, sizeof(offset), "%04x", at);
        out << "  " << offset << "  " << opName(op);
        out << std::dec;

        uint64_t immediate = 0;
        uint8_t count = 0;
        if (op == OP_MKREC || op == OP_SEND) { immediate = in.uvarint(); count = in.u8(); }
        if (op == OP_GETF) out << " field" << static_cast<int>(in.u8());
        if (shape.rel) out << " " << relName(in.u8());
        if (op == OP_MKREC) out << " " << program.strings.at(program.schemas.at(immediate).name);
        if (op == OP_SEND) {
            const EventDef* event = program.event(immediate);
            out << " " << (event != nullptr ? program.strings.at(event->name) : std::string("event?"));
        }
        if (shape.dst || op == OP_MKREC) out << " " << destination(in);
        uint8_t sources = shape.srcs;
        if (op == OP_MKREC) sources = count;
        if (op == OP_SEND) sources = static_cast<uint8_t>(count + 1);
        for (uint8_t i = 0; i < sources; ++i) {
            if (op == OP_MKREC || (op == OP_SEND && i > 0)) out << " f" << static_cast<int>(in.u8()) << "=";
            else out << " ";
            out << source(in, program);
        }
        if (shape.target) {
            char target[16];
            std::snprintf(target, sizeof(target), "%04x", in.u32());
            out << " -> @" << target;
        }
        lines.push_back(out.str());
    }
    return lines;
}

}  // namespace cvm
