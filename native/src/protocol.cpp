// Host protocol: length-prefixed messages and the value encoding they carry (docs/cvm-v2.md, section 8).
#include "protocol.hpp"

#include "opcodes.hpp"

#include <cstring>

namespace cvm {

void Writer::u8(uint8_t value) { out_.push_back(value); }

void Writer::u16(uint16_t value) { u8(static_cast<uint8_t>(value)); u8(static_cast<uint8_t>(value >> 8)); }

void Writer::u32(uint32_t value) { for (int i = 0; i < 4; ++i) u8(static_cast<uint8_t>(value >> (8 * i))); }

void Writer::u64(uint64_t value) { for (int i = 0; i < 8; ++i) u8(static_cast<uint8_t>(value >> (8 * i))); }

void Writer::f64(double value) { uint64_t bits; std::memcpy(&bits, &value, 8); u64(bits); }

void Writer::uvarint(uint64_t value) {
    while (value >= 0x80) { u8(static_cast<uint8_t>(value) | 0x80); value >>= 7; }
    u8(static_cast<uint8_t>(value));
}

void Writer::text(std::string_view value) {
    uvarint(value.size());
    out_.insert(out_.end(), value.begin(), value.end());
}

uint8_t Reader::u8() {
    if (pos_ >= data_.size()) throw ProtocolError("message ends too early");
    return data_[pos_++];
}

uint16_t Reader::u16() { uint16_t v = u8(); return static_cast<uint16_t>(v | (u8() << 8)); }

uint32_t Reader::u32() {
    uint32_t v = 0;
    for (int i = 0; i < 4; ++i) v |= static_cast<uint32_t>(u8()) << (8 * i);
    return v;
}

uint64_t Reader::u64() {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(u8()) << (8 * i);
    return v;
}

double Reader::f64() { uint64_t bits = u64(); double v; std::memcpy(&v, &bits, 8); return v; }

uint64_t Reader::uvarint() {
    uint64_t v = 0;
    int shift = 0;
    while (true) {
        uint8_t b = u8();
        if (shift == 63 && (b & 0xFE)) throw ProtocolError("varint overflows 64 bits");
        v |= static_cast<uint64_t>(b & 0x7F) << shift;
        if ((b & 0x80) == 0) return v;
        shift += 7;
        if (shift >= 64) throw ProtocolError("varint is too long");
    }
}

std::string_view Reader::text() {
    uint64_t size = uvarint();
    if (size > remaining()) throw ProtocolError("string runs past the end of the message");
    std::string_view value(reinterpret_cast<const char*>(data_.data() + pos_), size);
    pos_ += size;
    return value;
}

void writeValue(Writer& out, const Program& program, const TypeRef& type, const Value& value) {
    if (type.optional) {
        out.u8(value.isNone() ? 0 : 1);
        if (value.isNone()) return;
    } else if (value.isNone()) {
        throw ProtocolError("a value is missing where the schema requires one");
    }
    switch (type.tag) {
        case Tag::Bool: out.u8(value.b ? 1 : 0); break;
        case Tag::I64: out.u64(static_cast<uint64_t>(value.i)); break;
        case Tag::F64: out.f64(value.d); break;
        case Tag::Str: out.text(program.strings.at(value.str)); break;
        case Tag::List: {
            out.uvarint(value.block.count);
            for (uint32_t i = 0; i < value.block.count; ++i) writeValue(out, program, *type.element, value.block.items[i]);
            break;
        }
        case Tag::Rec: {
            const Schema& schema = program.schemas.at(type.schema);
            for (size_t i = 0; i < schema.fields.size(); ++i) writeValue(out, program, schema.fields[i].type, value.block.items[i]);
            break;
        }
        case Tag::None: break;
    }
}

void writeDynamic(Writer& out, const Program& program, const Value& value) {
    out.u8(static_cast<uint8_t>(value.tag));
    switch (value.tag) {
        case Tag::None: break;
        case Tag::Bool: out.u8(value.b ? 1 : 0); break;
        case Tag::I64: out.u64(static_cast<uint64_t>(value.i)); break;
        case Tag::F64: out.f64(value.d); break;
        case Tag::Str: out.text(program.strings.at(value.str)); break;
        case Tag::List:
            out.uvarint(value.block.count);
            for (uint32_t i = 0; i < value.block.count; ++i) writeDynamic(out, program, value.block.items[i]);
            break;
        case Tag::Rec:
            out.text(program.strings.at(program.schemas.at(value.schema).name));
            out.uvarint(value.block.count);
            for (uint32_t i = 0; i < value.block.count; ++i) writeDynamic(out, program, value.block.items[i]);
            break;
    }
}

Value readValue(Reader& in, Program& program, const TypeRef& type, Arena& arena) {
    if (type.optional) {
        uint8_t present = in.u8();
        if (present > 1) throw ProtocolError("invalid optional presence flag");
        if (present == 0) return Value::none();
    }
    switch (type.tag) {
        case Tag::Bool: {
            uint8_t value = in.u8();
            if (value > 1) throw ProtocolError("invalid boolean");
            return Value::boolean(value != 0);
        }
        case Tag::I64: return Value::integer(static_cast<int64_t>(in.u64()));
        case Tag::F64: {
            double value = in.f64();
            if (!std::isfinite(value)) throw ProtocolError("a number in a frame is not finite");
            return Value::real(value);
        }
        case Tag::Str: return Value::text(program.strings.intern(in.text()));
        case Tag::List: {
            uint64_t count = in.uvarint();
            if (count > 4096) throw ProtocolError("observation list is too long");
            Value* items = arena.allocate(count);
            for (uint64_t i = 0; i < count; ++i) items[i] = readValue(in, program, *type.element, arena);
            return Value::list(static_cast<uint32_t>(count), items);
        }
        case Tag::Rec: {
            const Schema& schema = program.schemas.at(type.schema);
            Value* items = arena.allocate(schema.fields.size());
            for (size_t i = 0; i < schema.fields.size(); ++i) items[i] = readValue(in, program, schema.fields[i].type, arena);
            return Value::record(type.schema, static_cast<uint32_t>(schema.fields.size()), items);
        }
        case Tag::None: return Value::none();
    }
    throw ProtocolError("unknown type in a frame");
}

}  // namespace cvm
