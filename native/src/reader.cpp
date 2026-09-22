// Loading a .cvm artifact: container, sections, and the tables the interpreter indexes into.
#include "cvm.hpp"
#include "opcodes.hpp"

#include <algorithm>
#include <cstring>

namespace cvm {

uint32_t StringTable::intern(std::string_view text) {
    auto found = index_.find(text);
    if (found != index_.end()) return found->second;
    if (values_.size() >= 65536 || text.size() > (1u << 20) - bytes_) throw LoadError("string table limit exceeded");
    values_.emplace_back(text);
    bytes_ += text.size();
    uint32_t id = static_cast<uint32_t>(values_.size() - 1);
    index_.emplace(std::string_view(values_.back()), id);
    return id;
}

const Behavior* Program::behavior(std::string_view name) const {
    for (const Behavior& item : behaviors) {
        if (strings.at(item.name) == name) return &item;
    }
    return nullptr;
}

const EventDef* Program::event(uint64_t id) const {
    for (const EventDef& item : events) {
        if (item.id == id) return &item;
    }
    return nullptr;
}

bool sameType(const TypeRef& a, const TypeRef& b) {
    if (a.tag != b.tag || a.optional != b.optional) return false;
    if (a.tag == Tag::Rec) return a.schema == b.schema;
    if (a.tag == Tag::List) return a.element && b.element && sameType(*a.element, *b.element);
    return true;
}

std::string typeName(const Program& program, const TypeRef& type) {
    std::string base;
    switch (type.tag) {
        case Tag::Bool: base = "Bool"; break;
        case Tag::I64: base = "I64"; break;
        case Tag::F64: base = "F64"; break;
        case Tag::Str: base = "Str"; break;
        case Tag::None: base = "None"; break;
        case Tag::List: base = "List<" + (type.element ? typeName(program, *type.element) : std::string("?")) + ">"; break;
        case Tag::Rec:
            base = type.schema < program.schemas.size() ? program.strings.at(program.schemas[type.schema].name) : "?";
            break;
    }
    return type.optional ? "Opt<" + base + ">" : base;
}

const char* opName(uint8_t op) {
    switch (op) {
        case OP_MOV: return "MOV"; case OP_POP: return "POP";
        case OP_ADD_I: return "ADD_I"; case OP_SUB_I: return "SUB_I"; case OP_MUL_I: return "MUL_I";
        case OP_MOD_I: return "MOD_I"; case OP_NEG_I: return "NEG_I";
        case OP_ADD_F: return "ADD_F"; case OP_SUB_F: return "SUB_F"; case OP_MUL_F: return "MUL_F";
        case OP_DIV_F: return "DIV_F"; case OP_NEG_F: return "NEG_F";
        case OP_TOF: return "TOF"; case OP_TOF_OPT: return "TOF_OPT";
        case OP_CLAMP_F: return "CLAMP_F"; case OP_CLAMP_I: return "CLAMP_I";
        case OP_CMP_I: return "CMP_I"; case OP_CMP_F: return "CMP_F"; case OP_CMP_N: return "CMP_N";
        case OP_EQ_S: return "EQ_S"; case OP_EQ_B: return "EQ_B"; case OP_EQ_A: return "EQ_A"; case OP_NOT: return "NOT";
        case OP_JMP: return "JMP"; case OP_BR_T: return "BR_T"; case OP_BR_F: return "BR_F";
        case OP_BR_CMP_I: return "BR_CMP_I"; case OP_BR_CMP_F: return "BR_CMP_F"; case OP_BR_CMP_N: return "BR_CMP_N";
        case OP_BR_EQ_S: return "BR_EQ_S"; case OP_BR_EQ_B: return "BR_EQ_B"; case OP_BR_EQ_A: return "BR_EQ_A";
        case OP_BR_SOME: return "BR_SOME"; case OP_BR_NONE: return "BR_NONE";
        case OP_LET_SOME: return "LET_SOME"; case OP_RET: return "RET";
        case OP_IS_SOME: return "IS_SOME"; case OP_UNWRAP: return "UNWRAP"; case OP_SOME: return "SOME";
        case OP_GETF: return "GETF"; case OP_INDEX: return "INDEX"; case OP_NEAREST: return "NEAREST";
        case OP_MKREC: return "MKREC"; case OP_CHANCE: return "CHANCE"; case OP_HAZARD: return "HAZARD";
        case OP_POWER: return "POWER"; case OP_MOTION: return "MOTION"; case OP_DAMAGE: return "DAMAGE";
        case OP_REPAIR: return "REPAIR"; case OP_SEND: return "SEND";
        default: return "?";
    }
}

OpShape shapeOf(uint8_t op) {
    OpShape s;
    s.known = true;
    switch (op) {
        case OP_MOV: case OP_NEG_I: case OP_NEG_F: case OP_TOF: case OP_TOF_OPT: case OP_NOT:
        case OP_IS_SOME: case OP_UNWRAP: case OP_SOME: case OP_NEAREST:
            s.dst = true; s.srcs = 1; break;
        case OP_GETF: s.dst = true; s.srcs = 1; break;   // also carries a field index
        case OP_POP: break;
        case OP_ADD_I: case OP_SUB_I: case OP_MUL_I: case OP_MOD_I:
        case OP_ADD_F: case OP_SUB_F: case OP_MUL_F: case OP_DIV_F:
        case OP_INDEX: case OP_CHANCE: case OP_HAZARD:
            s.dst = true; s.srcs = 2; break;
        case OP_CLAMP_F: case OP_CLAMP_I: s.dst = true; s.srcs = 3; break;
        case OP_CMP_I: case OP_CMP_F: case OP_CMP_N: case OP_EQ_S: case OP_EQ_B: case OP_EQ_A:
            s.dst = true; s.srcs = 2; s.rel = true; break;
        case OP_JMP: s.target = true; break;
        case OP_BR_T: case OP_BR_F: case OP_BR_SOME: case OP_BR_NONE: s.srcs = 1; s.target = true; break;
        case OP_BR_CMP_I: case OP_BR_CMP_F: case OP_BR_CMP_N: case OP_BR_EQ_S: case OP_BR_EQ_B: case OP_BR_EQ_A:
            s.srcs = 2; s.rel = true; s.target = true; break;
        case OP_LET_SOME: s.dst = true; s.srcs = 1; s.target = true; break;
        case OP_RET: break;
        case OP_MKREC: case OP_SEND: s.variadic = true; break;
        case OP_POWER: case OP_REPAIR: s.srcs = 1; break;
        case OP_MOTION: s.srcs = 2; break;
        case OP_DAMAGE: s.srcs = 3; break;
        default: s.known = false; break;
    }
    return s;
}

namespace {

/** Bounds-checked little-endian reader over the artifact bytes. */
class Reader {
public:
    Reader(const uint8_t* data, size_t size) : data_(data), size_(size) {}

    void need(size_t n) const {
        if (n > size_ - pos_) throw LoadError("artifact ends in the middle of a value");
    }
    uint8_t u8() { need(1); return data_[pos_++]; }
    uint16_t u16() { uint16_t v = u8(); return static_cast<uint16_t>(v | (u8() << 8)); }
    uint32_t u32() {
        uint32_t v = 0;
        for (int i = 0; i < 4; ++i) v |= static_cast<uint32_t>(u8()) << (8 * i);
        return v;
    }
    uint64_t uvarint() {
        uint64_t v = 0;
        int shift = 0;
        while (true) {
            uint8_t b = u8();
            if (shift == 63 && (b & 0xFE)) throw LoadError("varint overflows 64 bits");
            v |= static_cast<uint64_t>(b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift >= 64) throw LoadError("varint is too long");
        }
    }
    uint32_t uvarint32() {
        uint64_t v = uvarint();
        if (v > 0xFFFFFFFFull) throw LoadError("varint out of range");
        return static_cast<uint32_t>(v);
    }
    std::string_view bytes(size_t n) {
        need(n);
        std::string_view view(reinterpret_cast<const char*>(data_ + pos_), n);
        pos_ += n;
        return view;
    }
    size_t remaining() const { return size_ - pos_; }
    size_t position() const { return pos_; }

private:
    const uint8_t* data_;
    size_t size_;
    size_t pos_ = 0;
};

constexpr uint8_t kSectionStrings = 1;
constexpr uint8_t kSectionSchemas = 2;
constexpr uint8_t kSectionEvents = 3;
constexpr uint8_t kSectionBehaviors = 4;
constexpr uint8_t kSectionHash = 255;

/** Types are written as a tag tree; a record points at a schema index that may still be unread. */
TypeRef readType(Reader& in) {
    TypeRef type;
    uint8_t tag = in.u8();
    switch (tag) {
        case 1: type.tag = Tag::Bool; break;
        case 2: type.tag = Tag::I64; break;
        case 3: type.tag = Tag::F64; break;
        case 4: type.tag = Tag::Str; break;
        case 5: {
            TypeRef inner = readType(in);
            if (inner.optional) throw LoadError("Option of Option is not representable");
            type = inner;
            type.optional = true;
            break;
        }
        case 6:
            type.tag = Tag::List;
            type.element = std::make_shared<TypeRef>(readType(in));
            break;
        case 7: type.tag = Tag::Rec; type.schema = in.uvarint32(); break;
        default: throw LoadError("unknown type tag");
    }
    return type;
}

std::vector<Slot> readSlots(Reader& in, size_t stringCount) {
    uint32_t count = in.uvarint32();
    std::vector<Slot> slots;
    slots.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        Slot slot;
        slot.name = in.uvarint32();
        if (slot.name >= stringCount) throw LoadError("string index out of range");
        slot.type = readType(in);
        slots.push_back(std::move(slot));
    }
    return slots;
}

}  // namespace

std::unique_ptr<Program> loadArtifact(const std::vector<uint8_t>& bytes) {
    if (bytes.size() < 16 + 37) throw LoadError("artifact is too small");
    // The hash section is last and covers everything before it.
    size_t hashedSize = bytes.size() - 37;
    if (bytes[hashedSize] != kSectionHash) throw LoadError("artifact does not end with its hash");
    std::array<uint8_t, 32> digest = sha256(bytes.data(), hashedSize);
    if (std::memcmp(digest.data(), bytes.data() + bytes.size() - 32, 32) != 0) throw LoadError("artifact hash does not match");

    Reader in(bytes.data(), hashedSize);
    if (in.bytes(4) != "CVM2") throw LoadError("not a CVM artifact");
    uint16_t format = in.u16();
    if (format != 2) throw LoadError("unsupported artifact format " + std::to_string(format));

    auto program = std::make_unique<Program>();
    program->contract = in.u16();
    in.u32();
    in.u32();
    std::memcpy(program->hash.data(), bytes.data() + bytes.size() - 32, 32);

    bool haveStrings = false;
    while (in.remaining() > 0) {
        uint8_t id = in.u8();
        uint32_t length = in.u32();
        if (length > in.remaining()) throw LoadError("section runs past the end of the artifact");
        size_t start = in.position();
        Reader body(bytes.data() + start, length);
        switch (id) {
            case kSectionStrings: {
                uint32_t count = body.uvarint32();
                for (uint32_t i = 0; i < count; ++i) {
                    uint32_t size = body.uvarint32();
                    uint32_t interned = program->strings.intern(body.bytes(size));
                    if (interned != i) throw LoadError("duplicate string in the table");
                }
                haveStrings = true;
                break;
            }
            case kSectionSchemas: {
                if (!haveStrings) throw LoadError("schemas before strings");
                uint32_t count = body.uvarint32();
                for (uint32_t i = 0; i < count; ++i) {
                    Schema schema;
                    schema.name = body.uvarint32();
                    uint32_t fields = body.uvarint32();
                    for (uint32_t f = 0; f < fields; ++f) {
                        Field field;
                        field.name = body.uvarint32();
                        field.type = readType(body);
                        schema.fields.push_back(field);
                    }
                    program->schemas.push_back(std::move(schema));
                }
                for (Schema& schema : program->schemas) {
                    for (size_t f = 0; f < schema.fields.size(); ++f) {
                        const Field& field = schema.fields[f];
                        if (field.type.tag == Tag::Rec && field.type.schema >= program->schemas.size()) {
                            throw LoadError("schema index out of range");
                        }
                        const std::string& name = program->strings.at(field.name);
                        if (name == "id" && field.type.tag == Tag::Str) schema.idField = static_cast<int>(f);
                        if (name == "distance" && field.type.tag == Tag::F64) schema.distanceField = static_cast<int>(f);
                    }
                }
                break;
            }
            case kSectionEvents: {
                uint32_t count = body.uvarint32();
                for (uint32_t i = 0; i < count; ++i) {
                    EventDef event;
                    event.id = body.uvarint32();
                    event.name = body.uvarint32();
                    event.schema = body.uvarint32();
                    if (event.schema >= program->schemas.size()) throw LoadError("event schema out of range");
                    if (program->event(event.id) != nullptr) throw LoadError("duplicate event id");
                    program->events.push_back(event);
                }
                break;
            }
            case kSectionBehaviors: {
                program->stepText = program->strings.at(body.uvarint32());
                program->stepSeconds = std::stod(program->stepText);
                if (!(program->stepSeconds > 0.0)) throw LoadError("step must be positive");
                uint32_t count = body.uvarint32();
                for (uint32_t i = 0; i < count; ++i) {
                    Behavior behavior;
                    behavior.name = body.uvarint32();
                    behavior.kind = body.uvarint32();
                    behavior.capabilities = body.u32();
                    size_t strings = program->strings.size();
                    behavior.params = readSlots(body, strings);
                    behavior.state = readSlots(body, strings);
                    behavior.observes = readSlots(body, strings);
                    uint32_t handlers = body.uvarint32();
                    for (uint32_t h = 0; h < handlers; ++h) {
                        Handler handler;
                        handler.name = body.uvarint32();
                        handler.timer = body.u8() != 0;
                        handler.trigger = body.uvarint();
                        handler.entry = body.u32();
                        uint32_t locals = body.uvarint32();
                        for (uint32_t l = 0; l < locals; ++l) handler.locals.push_back(readType(body));
                        if (handler.timer && handler.trigger == 0) throw LoadError("timer period must be positive");
                        behavior.handlers.push_back(std::move(handler));
                    }
                    behavior.initEntry = body.u32();
                    uint32_t initLocals = body.uvarint32();
                    for (uint32_t l = 0; l < initLocals; ++l) behavior.initLocals.push_back(readType(body));
                    behavior.maxStack = body.uvarint32();
                    uint32_t codeSize = body.u32();
                    std::string_view code = body.bytes(codeSize);
                    behavior.code.assign(code.begin(), code.end());
                    uint32_t entries = body.uvarint32();
                    uint32_t offset = 0;
                    for (uint32_t e = 0; e < entries; ++e) {
                        offset += body.uvarint32();
                        uint32_t line = body.uvarint32();
                        uint32_t column = body.uvarint32();
                        behavior.sourceMap.push_back({offset, line, column});
                    }
                    program->behaviors.push_back(std::move(behavior));
                }
                break;
            }
            default:
                throw LoadError("unknown section " + std::to_string(id));
        }
        in.bytes(length);
    }

    if (program->behaviors.empty()) throw LoadError("artifact has no behaviors");
    for (const Behavior& behavior : program->behaviors) verifyBehavior(*program, behavior);
    return program;
}

}  // namespace cvm
