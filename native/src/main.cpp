// hh-vm: one OS process runs one entity. It loads a .cvm artifact, waits for frames from the host and answers
// with the intents and events its program produced (docs/cvm-v2.md).
#include "cvm.hpp"
#include "opcodes.hpp"
#include "protocol.hpp"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <optional>

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

}  // namespace

int main(int argc, char** argv) {
    std::string artifactPath;
    bool disasm = false;
    for (int i = 1; i < argc; ++i) {
        std::string argument = argv[i];
        if (argument == "--artifact" && i + 1 < argc) artifactPath = argv[++i];
        else if (argument == "--disasm") disasm = true;
        else if (argument == "--stdio") continue;
        else {
            std::fprintf(stderr, "usage: hh-vm --artifact FILE [--stdio | --disasm]\n");
            return 2;
        }
    }
    if (artifactPath.empty()) {
        std::fprintf(stderr, "usage: hh-vm --artifact FILE [--stdio | --disasm]\n");
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
        return serve(*program);
    } catch (const cvm::LoadError& failure) {
        std::fprintf(stderr, "load error: %s\n", failure.what());
        return 3;
    } catch (const std::exception& failure) {
        writeFault(failure.what());
        std::fprintf(stderr, "fault: %s\n", failure.what());
        return 4;
    }
}
