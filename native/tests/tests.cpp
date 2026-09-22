// Unit tests of the parts that do not need an artifact: hashing, the random stream and the value arena.
#include "cvm.hpp"
#include "protocol.hpp"

#include <cstdio>
#include <cstring>
#include <string>
#include <limits>

static int failures = 0;

static void check(bool ok, const std::string& what) {
    if (!ok) { std::printf("FAIL %s\n", what.c_str()); ++failures; }
}

static std::string hex(const std::array<uint8_t, 32>& digest, int bytes) {
    std::string out;
    char buffer[3];
    for (int i = 0; i < bytes; ++i) { std::snprintf(buffer, sizeof(buffer), "%02x", digest[i]); out += buffer; }
    return out;
}

int main() {
    // Known SHA-256 digests.
    check(hex(cvm::sha256(reinterpret_cast<const uint8_t*>(""), 0), 8) == "e3b0c44298fc1c14", "sha256 of the empty input");
    const char* abc = "abc";
    check(hex(cvm::sha256(reinterpret_cast<const uint8_t*>(abc), 3), 8) == "ba7816bf8f01cfea", "sha256 of abc");
    std::string long_input(1000, 'a');
    check(hex(cvm::sha256(reinterpret_cast<const uint8_t*>(long_input.data()), long_input.size()), 8) == "41edece42d63e8d9",
          "sha256 of a multi-block input");
    std::string exact(55, 'x');
    check(cvm::sha256(reinterpret_cast<const uint8_t*>(exact.data()), 55) != cvm::sha256(reinterpret_cast<const uint8_t*>(exact.data()), 54),
          "padding at the block boundary");

    // The first vector of conformance/prng.json, compared by its exact bit pattern.
    double draw = cvm::randomDraw(426, "home-1/heater", "HeaterControl", "apply", "strike", 0);
    uint64_t bits;
    std::memcpy(&bits, &draw, 8);
    char printed[32];
    std::snprintf(printed, sizeof(printed), "%016llx", static_cast<unsigned long long>(bits));
    check(std::string(printed) == "3feab8ec2c06f659", std::string("random draw, got ") + printed);
    check(cvm::randomDraw(426, "home-1/heater", "HeaterControl", "apply", "strike", 1) != draw, "the counter changes the draw");

    cvm::Arena arena(4);
    cvm::Value* first = arena.allocate(3);
    check(first != nullptr && first[0].isNone(), "the arena hands out cleared values");
    bool threw = false;
    try { arena.allocate(2); } catch (const cvm::StepError&) { threw = true; }
    check(threw, "the arena refuses to go past its capacity");
    arena.reset();
    check(arena.allocate(4) != nullptr, "a reset arena is empty again");

    cvm::Arena lazy((64u << 20) / sizeof(cvm::Value));
    check(lazy.reserved() == 0, "an idle VM does not reserve its arena limit");
    auto* saved = lazy.allocate(1);
    saved->i = 123;
    for (int n = 0; n < 20; ++n) lazy.allocate(512);
    check(saved->i == 123, "growing the arena preserves record pointers");
    check(lazy.reserved() * sizeof(cvm::Value) < (1u << 20), "small workloads allocate less than 1 MiB");
    auto reserved = lazy.reserved(); lazy.reset(); lazy.allocate(1);
    check(lazy.reserved() == reserved, "arena reuses its chunks after reset");

    for (auto bytes : {std::vector<uint8_t>{0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,1},
                       std::vector<uint8_t>{0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,2}}) {
        bool rejected = false;
        try { cvm::Reader reader(bytes); reader.text(); } catch (const cvm::ProtocolError&) { rejected = true; }
        check(rejected, "overflowing string length is rejected before any access");
    }
    cvm::StringTable strings;
    const auto firstId = strings.intern("first");
    for (int n = 0; n < 2000; ++n) strings.intern(std::to_string(n));
    check(strings.intern("first") == firstId, "interned string views survive table growth");

    cvm::Program program;
    auto rejectValue = [&](const cvm::TypeRef& type, const std::vector<uint8_t>& bytes, const char* label) {
        bool rejected = false;
        try { cvm::Reader reader(bytes); cvm::readValue(reader, program, type, arena); }
        catch (const cvm::ProtocolError&) { rejected = true; }
        check(rejected, label);
    };
    cvm::TypeRef boolean; boolean.tag = cvm::Tag::Bool;
    rejectValue(boolean, {2}, "invalid boolean tag");
    boolean.optional = true;
    rejectValue(boolean, {2}, "invalid option tag");
    rejectValue(boolean, {1}, "truncated option payload");
    cvm::TypeRef real; real.tag = cvm::Tag::F64;
    for (double value : {std::numeric_limits<double>::infinity(), -std::numeric_limits<double>::infinity(),
                         std::numeric_limits<double>::quiet_NaN()}) {
        cvm::Writer writer; writer.f64(value);
        rejectValue(real, writer.data(), "non-finite frame number");
    }
    cvm::TypeRef list; list.tag = cvm::Tag::List; list.element = std::make_shared<cvm::TypeRef>(real);
    cvm::Writer longList; longList.uvarint(4097);
    rejectValue(list, longList.data(), "oversized observation list");
    for (const auto& bytes : {std::vector<uint8_t>{0x80}, std::vector<uint8_t>(10, 0x80)}) {
        bool rejected = false;
        try { cvm::Reader reader(bytes); reader.uvarint(); } catch (const cvm::ProtocolError&) { rejected = true; }
        check(rejected, "truncated or unterminated varint");
    }

    if (failures == 0) std::printf("all native unit tests passed\n");
    return failures == 0 ? 0 : 1;
}
