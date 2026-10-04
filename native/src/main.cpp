// hh-vm: one OS process runs one entity. It loads a .cvm artifact, waits for frames from the host and answers
// with the intents and events its program produced (docs/cvm-v2.md).
#include "cvm.hpp"
#include "opcodes.hpp"
#include "protocol.hpp"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <optional>
#include <unordered_set>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#include <process.h>
static unsigned getProcessId() { return static_cast<unsigned>(_getpid()); }
#else
#include <unistd.h>
static unsigned getProcessId() { return static_cast<unsigned>(getpid()); }
#endif

namespace {

using namespace cvm;

std::vector<uint8_t> readFile(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) throw LoadError("cannot open " + path);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
}

/** Reads exactly [size] bytes from stdin; false at a clean end of input. */
bool readExact(uint8_t* data, size_t size) {
    size_t done = 0;
    while (done < size) {
        size_t got = std::fread(data + done, 1, size - done, stdin);
        if (got == 0) {
            if (done != 0) throw ProtocolError("truncated message");
            return false;
        }
        done += got;
    }
    return true;
}

void writeMessage(uint8_t type, const std::vector<uint8_t>& body) {
    if (body.size() + 1 > kMaxMessageSize) throw ProtocolError("outgoing message exceeds the protocol limit");
    uint8_t header[5];
    uint32_t length = static_cast<uint32_t>(body.size() + 1);
    for (int i = 0; i < 4; ++i) header[i] = static_cast<uint8_t>(length >> (8 * i));
    header[4] = type;
    std::fwrite(header, 1, 5, stdout);
    if (!body.empty()) std::fwrite(body.data(), 1, body.size(), stdout);
    std::fflush(stdout);
}

void writeFault(const std::string& message) {
    Writer out;
    out.text(message);
    writeMessage(MSG_FAULT, out.data());
}

/** One message from the host: its type and body, or nothing when the host closed the pipe. */
std::optional<std::pair<uint8_t, std::vector<uint8_t>>> readMessage() {
    uint8_t header[4];
    if (!readExact(header, 4)) return std::nullopt;
    uint32_t length = 0;
    for (int i = 0; i < 4; ++i) length |= static_cast<uint32_t>(header[i]) << (8 * i);
    if (length == 0 || length > kMaxMessageSize) throw ProtocolError("message length is out of range");
    std::vector<uint8_t> body(length);
    if (!readExact(body.data(), length)) throw ProtocolError("message is shorter than its length says");
    uint8_t type = body[0];
    body.erase(body.begin());
    return std::make_pair(type, std::move(body));
}

int currentProcessId() { return static_cast<int>(getProcessId()); }

/** Serves one entity until the host stops it or closes the pipe. */
int serve(Program& program) {
    std::unique_ptr<Vm> vm;
    const Behavior* behavior = nullptr;
    Arena frameArena(1u << 20);

    while (true) {
        std::optional<std::pair<uint8_t, std::vector<uint8_t>>> message = readMessage();
        if (!message) return 0;
        uint8_t type = message->first;
        Reader in(message->second);

        if (type == MSG_STOP) {
            if (in.remaining() != 0) throw ProtocolError("STOP has trailing bytes");
            return 0;
        }

        if (type == MSG_INIT) {
            if (vm) throw ProtocolError("INIT sent twice");
            uint16_t version = in.u16();
            if (version != kProtocolVersion) throw ProtocolError("unsupported protocol version");
            std::string runId(in.text());
            std::string entityId(in.text());
            std::string behaviorName(in.text());
            int64_t seed = static_cast<int64_t>(in.u64());
            std::span<const uint8_t> hash = in.bytes(32);
            if (std::memcmp(hash.data(), program.hash.data(), 32) != 0) throw ProtocolError("the host expects a different artifact");
            behavior = program.behavior(behaviorName);
            if (behavior == nullptr) throw ProtocolError("unknown behavior " + behaviorName);
            std::vector<Value> params;
            for (const Slot& slot : behavior->params) params.push_back(readValue(in, program, slot.type, frameArena));
            if (in.remaining() != 0) throw ProtocolError("INIT has trailing bytes");
            vm = std::make_unique<Vm>(program, *behavior, entityId, seed, std::move(params));

            Writer out;
            out.u16(kProtocolVersion);
            out.u32(static_cast<uint32_t>(currentProcessId()));
            for (size_t i = 0; i < behavior->state.size(); ++i) {
                writeValue(out, program, behavior->state[i].type, vm->state()[i]);
            }
            writeMessage(MSG_READY, out.data());
            continue;
        }

        if (type != MSG_FRAME) throw ProtocolError("unexpected message before a frame");
        if (!vm) throw ProtocolError("a frame arrived before INIT");

        frameArena.reset();
        uint64_t tick = in.uvarint();
        std::vector<Value> observations;
        observations.reserve(behavior->observes.size());
        for (const Slot& slot : behavior->observes) observations.push_back(readValue(in, program, slot.type, frameArena));

        std::vector<DeliveredEvent> events;
        uint64_t count = in.uvarint();
        if (count > 65536) throw ProtocolError("too many events in one frame");
        for (uint64_t i = 0; i < count; ++i) {
            DeliveredEvent event;
            event.eventId = in.uvarint();
            event.sender = program.strings.intern(in.text());
            event.sequence = in.uvarint();
            const EventDef* definition = program.event(event.eventId);
            if (definition == nullptr) throw ProtocolError("frame carries an unknown event");
            const Schema& schema = program.schemas.at(definition->schema);
            Value* items = frameArena.allocate(schema.fields.size());
            for (size_t f = 0; f < schema.fields.size(); ++f) items[f] = readValue(in, program, schema.fields[f].type, frameArena);
            event.payload = Value::record(definition->schema, static_cast<uint32_t>(schema.fields.size()), items);
            events.push_back(std::move(event));
        }
        if (in.remaining() != 0) throw ProtocolError("frame has trailing bytes");

        Writer out;
        try {
            StepResult result = vm->step(tick, observations, std::move(events));
            out.u8(0);
            out.uvarint(result.intents.size());
            for (const Intent& intent : result.intents) {
                out.u8(intent.operation);
                out.uvarint(intent.arguments.size());
                for (const Value& argument : intent.arguments) writeDynamic(out, program, argument);
            }
            out.uvarint(result.events.size());
            for (const OutgoingEvent& event : result.events) {
                out.text(program.strings.at(event.target));
                out.uvarint(event.eventId);
                out.uvarint(event.sequence);
                const EventDef* definition = program.event(event.eventId);
                const Schema& schema = program.schemas.at(definition->schema);
                for (size_t f = 0; f < schema.fields.size(); ++f) {
                    writeValue(out, program, schema.fields[f].type, event.payload.block.items[f]);
                }
            }
            for (size_t i = 0; i < behavior->state.size(); ++i) {
                writeValue(out, program, behavior->state[i].type, vm->state()[i]);
            }
            out.u32(static_cast<uint32_t>(result.instructions));
        } catch (const StepError& failure) {
            out.clear();
            out.u8(1);
            out.text(failure.what());
            for (size_t i = 0; i < behavior->state.size(); ++i) {
                writeValue(out, program, behavior->state[i].type, vm->state()[i]);
            }
        }
        writeMessage(MSG_RESULT, out.data());
    }
}

/** One native process shares its verified Program across many isolated entity VMs. */
int serveBatch(Program& program) {
    struct Context {
        std::unique_ptr<Vm> vm;
        const Behavior* behavior = nullptr;
        Arena paramsArena{1u << 20};
        Arena frameArena{1u << 20};
    };
    std::map<std::string, Context> contexts;
    constexpr uint64_t kMaxBatch = 1024;
    while (true) {
        auto message = readMessage();
        if (!message) return 0;
        Reader in(message->second);
        if (message->first == MSG_STOP) {
            if (in.remaining() != 0) throw ProtocolError("STOP has trailing bytes");
            return 0;
        }
        if (message->first == MSG_BATCH_INIT) {
            if (in.u16() != kProtocolVersion) throw ProtocolError("unsupported protocol version");
            (void)in.text(); // run ID is diagnostic metadata, each context still has its own seed and ID.
            auto hash = in.bytes(32);
            if (std::memcmp(hash.data(), program.hash.data(), 32) != 0) throw ProtocolError("the host expects a different artifact");
            uint64_t count = in.uvarint();
            if (count == 0 || count > kMaxBatch) throw ProtocolError("context batch size is out of range");
            struct Pending { std::string id; Context context; };
            std::vector<Pending> pending;
            std::unordered_set<std::string> pendingIds;
            pending.reserve(count);
            for (uint64_t i = 0; i < count; ++i) {
                std::string id(in.text());
                std::string behaviorName(in.text());
                int64_t seed = static_cast<int64_t>(in.u64());
                if (id.empty() || contexts.contains(id) || !pendingIds.insert(id).second)
                    throw ProtocolError("duplicate or empty context ID");
                const Behavior* behavior = program.behavior(behaviorName);
                if (!behavior) throw ProtocolError("unknown behavior " + behaviorName);
                std::vector<Value> params;
                params.reserve(behavior->params.size());
                Context context;
                context.behavior = behavior;
                for (const Slot& slot : behavior->params) params.push_back(readValue(in, program, slot.type, context.paramsArena));
                context.vm = std::make_unique<Vm>(program, *behavior, id, seed, std::move(params));
                pending.push_back({std::move(id), std::move(context)});
            }
            if (in.remaining() != 0) throw ProtocolError("BATCH_INIT has trailing bytes");
            for (auto& entry : pending) contexts.emplace(entry.id, std::move(entry.context));
            Writer out;
            out.u16(kProtocolVersion);
            out.uvarint(pending.size());
            for (const auto& entry : pending) {
                out.text(entry.id);
                out.u32(static_cast<uint32_t>(currentProcessId()));
                const Context& context = contexts.at(entry.id);
                for (size_t i = 0; i < context.behavior->state.size(); ++i)
                    writeValue(out, program, context.behavior->state[i].type, context.vm->state()[i]);
            }
            writeMessage(MSG_BATCH_READY, out.data());
            continue;
        }
        if (message->first != MSG_BATCH_FRAME) throw ProtocolError("unexpected message type in shared-context mode");
        uint64_t tick = in.uvarint();
        uint64_t count = in.uvarint();
        if (count == 0 || count > kMaxBatch) throw ProtocolError("frame batch size is out of range");
        struct InputFrame { std::string id; Context* context; std::vector<Value> observations; std::vector<DeliveredEvent> events; };
        std::vector<InputFrame> frames;
        frames.reserve(count);
        std::unordered_set<std::string> seen;
        for (uint64_t i = 0; i < count; ++i) {
            std::string id(in.text());
            auto found = contexts.find(id);
            if (found == contexts.end() || !seen.insert(id).second) throw ProtocolError("unknown or duplicate frame context");
            Context& context = found->second;
            context.frameArena.reset();
            std::vector<Value> observations;
            observations.reserve(context.behavior->observes.size());
            for (const Slot& slot : context.behavior->observes) observations.push_back(readValue(in, program, slot.type, context.frameArena));
            uint64_t eventCount = in.uvarint();
            if (eventCount > 65536) throw ProtocolError("too many events in one frame");
            std::vector<DeliveredEvent> events;
            events.reserve(eventCount);
            for (uint64_t e = 0; e < eventCount; ++e) {
                DeliveredEvent event;
                event.eventId = in.uvarint();
                event.sender = program.strings.intern(in.text());
                event.sequence = in.uvarint();
                const EventDef* definition = program.event(event.eventId);
                if (!definition) throw ProtocolError("frame carries an unknown event");
                const Schema& schema = program.schemas.at(definition->schema);
                Value* fields = context.frameArena.allocate(schema.fields.size());
                for (size_t f = 0; f < schema.fields.size(); ++f) fields[f] = readValue(in, program, schema.fields[f].type, context.frameArena);
                event.payload = Value::record(definition->schema, static_cast<uint32_t>(schema.fields.size()), fields);
                events.push_back(std::move(event));
            }
            frames.push_back({std::move(id), &context, std::move(observations), std::move(events)});
        }
        if (in.remaining() != 0) throw ProtocolError("BATCH_FRAME has trailing bytes");
        Writer out;
        out.uvarint(frames.size());
        for (InputFrame& frame : frames) {
            Writer resultOut;
            try {
                StepResult result = frame.context->vm->step(tick, frame.observations, std::move(frame.events));
                resultOut.u8(0);
                resultOut.uvarint(result.intents.size());
                for (const Intent& intent : result.intents) {
                    resultOut.u8(intent.operation); resultOut.uvarint(intent.arguments.size());
                    for (const Value& argument : intent.arguments) writeDynamic(resultOut, program, argument);
                }
                resultOut.uvarint(result.events.size());
                for (const OutgoingEvent& event : result.events) {
                    resultOut.text(program.strings.at(event.target)); resultOut.uvarint(event.eventId); resultOut.uvarint(event.sequence);
                    const EventDef* definition = program.event(event.eventId);
                    const Schema& schema = program.schemas.at(definition->schema);
                    for (size_t f = 0; f < schema.fields.size(); ++f)
                        writeValue(resultOut, program, schema.fields[f].type, event.payload.block.items[f]);
                }
                for (size_t i = 0; i < frame.context->behavior->state.size(); ++i)
                    writeValue(resultOut, program, frame.context->behavior->state[i].type, frame.context->vm->state()[i]);
                resultOut.u32(static_cast<uint32_t>(result.instructions));
            } catch (const StepError& failure) {
                resultOut.clear(); resultOut.u8(1); resultOut.text(failure.what());
                for (size_t i = 0; i < frame.context->behavior->state.size(); ++i)
                    writeValue(resultOut, program, frame.context->behavior->state[i].type, frame.context->vm->state()[i]);
            }
            out.text(frame.id); out.uvarint(resultOut.data().size()); out.bytes(resultOut.data().data(), resultOut.data().size());
        }
        writeMessage(MSG_BATCH_RESULT, out.data());
    }
}

}  // namespace

int main(int argc, char** argv) {
    std::string artifactPath;
    bool disasm = false;
    bool batch = false;
    for (int i = 1; i < argc; ++i) {
        std::string argument = argv[i];
        if (argument == "--artifact" && i + 1 < argc) artifactPath = argv[++i];
        else if (argument == "--disasm") disasm = true;
        else if (argument == "--batch") batch = true;
        else if (argument == "--stdio") continue;
        else {
            std::fprintf(stderr, "usage: hh-vm --artifact FILE [--stdio [--batch] | --disasm]\n");
            return 2;
        }
    }
    if (artifactPath.empty()) {
        std::fprintf(stderr, "usage: hh-vm --artifact FILE [--stdio [--batch] | --disasm]\n");
        return 2;
    }

#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif

    try {
        std::unique_ptr<cvm::Program> program = cvm::loadArtifact(readFile(artifactPath));
        if (disasm) {
            for (const cvm::Behavior& behavior : program->behaviors) {
                for (const std::string& line : cvm::disassemble(*program, behavior)) std::printf("%s\n", line.c_str());
            }
            return 0;
        }
        return batch ? serveBatch(*program) : serve(*program);
    } catch (const cvm::LoadError& failure) {
        std::fprintf(stderr, "load error: %s\n", failure.what());
        return 3;
    } catch (const std::exception& failure) {
        writeFault(failure.what());
        std::fprintf(stderr, "fault: %s\n", failure.what());
        return 4;
    }
}
