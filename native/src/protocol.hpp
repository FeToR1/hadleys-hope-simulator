// Framing and value encoding between the host and one VM process (docs/cvm-v2.md, section 8).
#pragma once

#include "cvm.hpp"

#include <cmath>
#include <span>
#include <string_view>
#include <vector>

namespace cvm {

/** The host sent something that does not follow the protocol; the process reports it and stops. */
struct ProtocolError : std::runtime_error {
    explicit ProtocolError(const std::string& what) : std::runtime_error(what) {}
};

enum MessageType : uint8_t {
    MSG_INIT = 1, MSG_READY = 2, MSG_FRAME = 3, MSG_RESULT = 4, MSG_STOP = 5, MSG_FAULT = 6,
};

constexpr uint16_t kProtocolVersion = 1;

/** Largest message the process accepts, so a wrong length cannot make it allocate without bound. */
constexpr uint32_t kMaxMessageSize = 64u << 20;

class Writer {
public:
    void u8(uint8_t value);
    void u16(uint16_t value);
    void u32(uint32_t value);
    void u64(uint64_t value);
    void f64(double value);
    void uvarint(uint64_t value);
    void text(std::string_view value);
    void bytes(const uint8_t* data, size_t size) { out_.insert(out_.end(), data, data + size); }
    const std::vector<uint8_t>& data() const { return out_; }
    void clear() { out_.clear(); }

private:
    std::vector<uint8_t> out_;
};

class Reader {
public:
    explicit Reader(std::span<const uint8_t> data) : data_(data) {}
    uint8_t u8();
    uint16_t u16();
    uint32_t u32();
    uint64_t u64();
    double f64();
    uint64_t uvarint();
    std::string_view text();
    std::span<const uint8_t> bytes(size_t size) {
        if (size > remaining()) throw ProtocolError("message ends too early");
        std::span<const uint8_t> value = data_.subspan(pos_, size);
        pos_ += size;
        return value;
    }
    size_t remaining() const { return data_.size() - pos_; }

private:
    std::span<const uint8_t> data_;
    size_t pos_ = 0;
};

/** Values are encoded by their declared type, so nothing but the payload travels. */
void writeValue(Writer& out, const Program& program, const TypeRef& type, const Value& value);
Value readValue(Reader& in, Program& program, const TypeRef& type, Arena& arena);

/**
 * An intent argument carries its own tag, because the shape of an intent belongs to the world kernel rather
 * than to the program: 0 none, 1 bool, 2 int, 3 real, 4 string, 6 list, 7 record (schema name, then fields).
 */
void writeDynamic(Writer& out, const Program& program, const Value& value);

}  // namespace cvm
