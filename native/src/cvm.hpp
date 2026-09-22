// Colony stack VM: values, program image and the pieces the interpreter needs.
// The format is docs/cvm-v2.md; the Kotlin compiler in colony-dsl-parser writes what this loads.
#pragma once

#include <array>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace cvm {

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/** The artifact is not loadable: bad container, failed verification, unsupported version. */
struct LoadError : std::runtime_error {
    explicit LoadError(const std::string& what) : std::runtime_error(what) {}
};

/** The step failed: division by zero, overflow, a limit, none unwrapped. The step is rolled back. */
struct StepError : std::runtime_error {
    explicit StepError(const std::string& what) : std::runtime_error(what) {}
};

// ---------------------------------------------------------------------------
// Types and values
// ---------------------------------------------------------------------------

enum class Tag : uint8_t { None = 0, Bool = 1, I64 = 2, F64 = 3, Str = 4, List = 6, Rec = 7 };

/**
 * A declared type. Option is a flag rather than a wrapper, because the language forbids Option of Option and
 * the runtime representation of some(x) is x itself.
 */
struct TypeRef {
    Tag tag = Tag::None;
    /** For Rec the schema index. */
    uint32_t schema = 0;
    /** True when the slot may also hold none. */
    bool optional = false;
    /** Element type of a list. */
    std::shared_ptr<TypeRef> element;
};

/** Structural equality of two declared types. */
bool sameType(const TypeRef& a, const TypeRef& b);

struct Program;
struct Value;

/** Elements of a list or fields of a record, allocated in the step arena; trivial, so it can live in a union. */
struct Block {
    uint32_t count;
    Value* items;
};

/**
 * A runtime value (24 bytes on the supported 64-bit builds). An Option is flat: none is Tag::None, some(x) is x itself, which the language
 * allows because Option<Option<T>> is rejected by the compiler.
 */
struct Value {
    Tag tag = Tag::None;
    /** Schema of a record, so a record knows its own shape. */
    uint32_t schema = 0;
    union {
        bool b;
        int64_t i;
        double d;
        uint32_t str;   // index into the string table
        Block block;    // list elements or record fields
    };

    Value() : tag(Tag::None), schema(0), i(0) {}
    static Value none() { return Value(); }
    static Value boolean(bool v) { Value x; x.tag = Tag::Bool; x.b = v; return x; }
    static Value integer(int64_t v) { Value x; x.tag = Tag::I64; x.i = v; return x; }
    static Value real(double v) { Value x; x.tag = Tag::F64; x.d = v; return x; }
    static Value text(uint32_t id) { Value x; x.tag = Tag::Str; x.str = id; return x; }
    static Value list(uint32_t count, Value* items) { Value x; x.tag = Tag::List; x.block.count = count; x.block.items = items; return x; }
    static Value record(uint32_t schema, uint32_t count, Value* items) {
        Value x; x.tag = Tag::Rec; x.schema = schema; x.block.count = count; x.block.items = items; return x;
    }

    bool isNone() const { return tag == Tag::None; }
};

/** Lazily allocated, bounded arena. Chunks never move, so existing record/list pointers stay valid. */
class Arena {
public:
    explicit Arena(size_t capacity) : capacity_(capacity) {}
    Value* allocate(size_t count) {
        if (count == 0) return nullptr;
        if (count > capacity_ - used_) throw StepError("value arena exhausted");
        while (current_ < chunks_.size() && count > chunks_[current_].capacity - chunks_[current_].used) ++current_;
        if (current_ == chunks_.size()) {
            if (count > capacity_ - reserved_) throw StepError("value arena exhausted");
            const size_t size = std::min(capacity_ - reserved_, std::max(size_t(256), count));
            chunks_.push_back({std::make_unique<Value[]>(size), size, 0});
            reserved_ += size;
        }
        Chunk& chunk = chunks_[current_];
        Value* start = chunk.values.get() + chunk.used;
        chunk.used += count;
        used_ += count;
        for (size_t i = 0; i < count; ++i) start[i] = Value();
        return start;
    }
    void reset() { for (auto& chunk : chunks_) chunk.used = 0; used_ = 0; current_ = 0; }
    size_t used() const { return used_; }
    size_t reserved() const { return reserved_; }

private:
    struct Chunk { std::unique_ptr<Value[]> values; size_t capacity; size_t used; };
    std::vector<Chunk> chunks_;
    size_t capacity_, used_ = 0, reserved_ = 0, current_ = 0;
};

/**
 * Strings are compared and stored as ids; the program's own strings come first and never change.
 * The storage is a deque because the index holds views into it: a vector would move its elements as it grows
 * and leave every key dangling, which showed up as two equal strings comparing unequal.
 */
class StringTable {
public:
    uint32_t intern(std::string_view text);
    const std::string& at(uint32_t id) const { return values_.at(id); }
    size_t size() const { return values_.size(); }

private:
    std::deque<std::string> values_;
    std::unordered_map<std::string_view, uint32_t> index_;
    size_t bytes_ = 0;
};

// ---------------------------------------------------------------------------
// Program image
// ---------------------------------------------------------------------------

struct Field {
    uint32_t name = 0;      // string id
    TypeRef type;
};

struct Schema {
    uint32_t name = 0;
    std::vector<Field> fields;
    /** Fields NEAREST orders by; -1 when this schema has none, which makes it an invalid argument. */
    int idField = -1;
    int distanceField = -1;
};

struct EventDef {
    uint32_t id = 0;
    uint32_t name = 0;
    uint32_t schema = 0;
};

struct Slot {
    uint32_t name = 0;
    TypeRef type;
};

struct Handler {
    uint32_t name = 0;
    bool timer = false;
    /** Event id for a subscription, period in ticks for a timer. */
    uint64_t trigger = 0;
    uint32_t entry = 0;
    std::vector<TypeRef> locals;
};

struct Behavior {
    uint32_t name = 0;
    uint32_t kind = 0;
    uint32_t capabilities = 0;
    std::vector<Slot> params;
    std::vector<Slot> state;
    std::vector<Slot> observes;
    std::vector<Handler> handlers;
    uint32_t initEntry = 0;
    std::vector<TypeRef> initLocals;
    uint32_t maxStack = 0;
    std::vector<uint8_t> code;
    /** Offset -> line, column; sorted by offset. */
    std::vector<std::array<uint32_t, 3>> sourceMap;
};

struct Program {
    uint16_t contract = 0;
    double stepSeconds = 1.0;
    std::string stepText;
    StringTable strings;
    std::vector<Schema> schemas;
    std::vector<EventDef> events;
    std::vector<Behavior> behaviors;
    /** SHA-256 of the artifact, as the host sends it in INIT. */
    std::array<uint8_t, 32> hash{};

    const Behavior* behavior(std::string_view name) const;
    const EventDef* event(uint64_t id) const;
};

/** Human-readable type, for verifier messages. */
std::string typeName(const Program& program, const TypeRef& type);

/** Reads and verifies an artifact; throws LoadError when anything is wrong. */
std::unique_ptr<Program> loadArtifact(const std::vector<uint8_t>& bytes);

/** Verification of one behavior's code: types, stack, slots, targets, capabilities. */
void verifyBehavior(const Program& program, const Behavior& behavior);

/** Instruction listing, for --disasm and for error messages. */
std::vector<std::string> disassemble(const Program& program, const Behavior& behavior);

// ---------------------------------------------------------------------------
// Random numbers
// ---------------------------------------------------------------------------

std::array<uint8_t, 32> sha256(const uint8_t* data, size_t size);

/**
 * One draw of the documented stream: seed, four length-prefixed UTF-8 keys and the counter, hashed with SHA-256,
 * the top 53 bits of the first eight bytes divided by 2^53.
 */
double randomDraw(int64_t seed, std::string_view entity, std::string_view behavior,
                  std::string_view rule, std::string_view site, uint64_t counter);

// ---------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------

/** Limits of one step (docs/cvm-v2.md, section 7). */
struct Limits {
    uint64_t instructions = 100000;
    uint32_t stack = 4096;
    uint32_t outputs = 1024;
    size_t arenaValues = (64u << 20) / sizeof(Value);  // 64 MiB upper bound, allocated on demand
};

struct DeliveredEvent {
    uint64_t eventId = 0;
    uint32_t sender = 0;   // string id
    uint64_t sequence = 0;
    Value payload;         // record of the event schema
};

struct Intent {
    uint8_t operation = 0; // POWER, MOTION, DAMAGE, REPAIR opcodes
    std::vector<Value> arguments;
};

struct OutgoingEvent {
    uint32_t target = 0;   // string id
    uint64_t eventId = 0;
    uint64_t sequence = 0;
    Value payload;
};

struct StepResult {
    std::vector<Intent> intents;
    std::vector<OutgoingEvent> events;
    uint64_t instructions = 0;
};

/** One entity: its program, its private state and its random streams. */
class Vm {
public:
    /** [program] is not const because the entity id is interned into its string table. */
    Vm(Program& program, const Behavior& behavior, std::string entityId, int64_t seed,
       std::vector<Value> params, const Limits& limits = Limits());

    /** State after the initializers, in slot order. */
    const std::vector<Value>& state() const { return state_; }
    const Behavior& behavior() const { return behavior_; }
    const Program& program() const { return program_; }

    /**
     * Runs one step: the delivered events in (sender, sequence) order, then the timers that are due.
     * On a StepError the state, the random counters and the outgoing sequence are left as they were.
     * Records and lists in the result live in the step arena, so the caller reads them before the next step.
     */
    StepResult step(uint64_t tick, const std::vector<Value>& observations, std::vector<DeliveredEvent> events);

private:
    struct Frame;
    void run(uint32_t entry, const Handler* handler, Frame& frame, StepResult& out);
    double draw(std::string_view rule, std::string_view site);

    Program& program_;
    const Behavior& behavior_;
    std::string entityId_;
    uint32_t selfId_ = 0;
    std::string behaviorName_;
    int64_t seed_;
    Limits limits_;
    std::vector<Value> params_;
    std::vector<Value> state_;
    std::unordered_map<std::string, uint64_t> counters_;
    uint64_t sequence_ = 0;
    Arena arena_;
    uint64_t budget_ = 0;
    std::vector<Value> stack_;
};

}  // namespace cvm
