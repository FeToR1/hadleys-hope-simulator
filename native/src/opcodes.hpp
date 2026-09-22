// Instruction and operand encoding, mirroring colony.cvm.Op and colony.cvm.Codec (docs/cvm-v2.md, sections 4-5).
#pragma once

#include <cstdint>

namespace cvm {

enum Op : uint8_t {
    OP_MOV = 0x01, OP_POP = 0x02,
    OP_ADD_I = 0x10, OP_SUB_I = 0x11, OP_MUL_I = 0x12, OP_MOD_I = 0x13, OP_NEG_I = 0x14,
    OP_ADD_F = 0x18, OP_SUB_F = 0x19, OP_MUL_F = 0x1A, OP_DIV_F = 0x1B, OP_NEG_F = 0x1C,
    OP_TOF = 0x1D, OP_TOF_OPT = 0x1E, OP_CLAMP_F = 0x1F, OP_CLAMP_I = 0x20,
    OP_CMP_I = 0x24, OP_CMP_F = 0x25, OP_CMP_N = 0x26,
    OP_EQ_S = 0x27, OP_EQ_B = 0x28, OP_EQ_A = 0x29, OP_NOT = 0x2A,
    OP_JMP = 0x30, OP_BR_T = 0x31, OP_BR_F = 0x32,
    OP_BR_CMP_I = 0x33, OP_BR_CMP_F = 0x34, OP_BR_CMP_N = 0x35,
    OP_BR_EQ_S = 0x36, OP_BR_EQ_B = 0x37, OP_BR_EQ_A = 0x38,
    OP_BR_SOME = 0x39, OP_BR_NONE = 0x3A, OP_LET_SOME = 0x3B, OP_RET = 0x3C,
    OP_IS_SOME = 0x40, OP_UNWRAP = 0x41, OP_SOME = 0x42,
    OP_GETF = 0x43, OP_INDEX = 0x44, OP_NEAREST = 0x45, OP_MKREC = 0x46,
    OP_CHANCE = 0x50, OP_HAZARD = 0x51,
    OP_POWER = 0x60, OP_MOTION = 0x61, OP_DAMAGE = 0x62, OP_REPAIR = 0x63, OP_SEND = 0x64,
};

enum Rel : uint8_t { REL_EQ = 0, REL_NE = 1, REL_LT = 2, REL_LE = 3, REL_GT = 4, REL_GE = 5 };

enum SrcMode : uint8_t {
    SRC_STACK = 0, SRC_IMM_I = 1, SRC_IMM_F = 2, SRC_IMM_S = 3, SRC_SMALL = 4,
    SRC_VIEW = 5, SRC_STATE = 6, SRC_LOCAL = 7, SRC_PARAM = 8, SRC_MSG = 9,
    SRC_LOCAL_FIELD = 10, SRC_VIEW_FIELD = 11, SRC_SPECIAL = 12,
};

enum DstMode : uint8_t { DST_STACK = 0, DST_STATE = 1, DST_LOCAL = 2 };

enum SmallConst : uint8_t {
    SMALL_FALSE = 0, SMALL_TRUE = 1, SMALL_NONE = 2, SMALL_I0 = 3, SMALL_I1 = 4, SMALL_IM1 = 5,
    SMALL_F0 = 6, SMALL_F1 = 7,
};

enum Special : uint8_t { SPECIAL_TIME = 0, SPECIAL_SELF = 1, SPECIAL_MSG = 2 };

enum Capability : uint32_t { CAP_POWER = 1, CAP_DAMAGE = 2, CAP_MOTION = 4, CAP_REPAIR = 8 };

/** The index nibble 15 means the index follows as a varint. */
constexpr uint8_t kExtendedIndex = 15;

const char* opName(uint8_t op);

/** Shape of an instruction: how many source operands it has and whether it carries a destination, rel or target. */
struct OpShape {
    bool known = false;
    bool dst = false;
    uint8_t srcs = 0;
    bool rel = false;
    bool target = false;
    /** MKREC and SEND carry a variable number of field/operand pairs. */
    bool variadic = false;
};

OpShape shapeOf(uint8_t op);

}  // namespace cvm
