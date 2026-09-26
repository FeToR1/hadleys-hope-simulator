#include <unordered_map>
#include <string_view>
#include <algorithm>
#include <iostream>
#include <charconv>
#include <cstdint>
#include <utility>
#include <sstream>
#include <fstream>
#include <memory>
#include <string>
#include <vector>
#include <new>

#include <hhvm/hhvm.hpp>

namespace
{
struct mnemonic_entry
{
    std::string_view name;
    hhvm::opcode     code;
    bool             needs_operand;
};

constexpr mnemonic_entry mnemonics[] = {
    { "nop",           hhvm::op_nop,           false },
    { "halt",          hhvm::op_halt,          false },
    { "push",          hhvm::op_push,          true  },
    { "pop",           hhvm::op_pop,           false },
    { "inc",           hhvm::op_inc,           false },
    { "dec",           hhvm::op_dec,           false },
    { "add",           hhvm::op_add,           false },
    { "sub",           hhvm::op_sub,           false },
    { "mul",           hhvm::op_mul,           false },
    { "div",           hhvm::op_div,           false },
    { "and",           hhvm::op_and,           false },
    { "or",            hhvm::op_or,            false },
    { "xor",           hhvm::op_xor,           false },
    { "shl",           hhvm::op_shl,           false },
    { "shr",           hhvm::op_shr,           false },
    { "call",          hhvm::op_call,          true  },
    { "ret",           hhvm::op_ret,           false },
    { "syscall",       hhvm::op_syscall,       true  },
    { "jump",          hhvm::op_jump,          true  },
    { "jump_if_true",  hhvm::op_jump_if_true,  true  },
    { "jump_if_false", hhvm::op_jump_if_false, true  },
    { "load",          hhvm::op_load,          true  },
    { "store",         hhvm::op_store,         true  },
    { "arg",           hhvm::op_arg,           true  },
};

struct assembled
{
    std::uint32_t                    entry = 0;
    std::vector< hhvm::image::op >   ops;
    std::vector< hhvm::image::func > funcs;
};

std::string strip(const std::string & _line)
{
    auto s       = _line.substr(0, _line.find(';'));
    const auto b = s.find_first_not_of(" \t\r\n");

    if (b == std::string::npos) {
        return {};
    }

    const auto e = s.find_last_not_of(" \t\r\n");

    return s.substr(b, e - b + 1);
}

std::expected< assembled, std::errc > assemble_text(std::istream & _in)
{
    std::vector< std::string > lines = {};
    for (std::string line; std::getline(_in, line);) {
        lines.push_back(line);
    }

    std::unordered_map< std::string, std::uint32_t > labels      = {};
    std::unordered_map< std::string, std::uint32_t > func_ids    = {};
    assembled                                        out         = {};
    std::string                                      entry_label = {};
    bool                                             has_entry   = false;

    std::uint32_t addr = 0;
    for (const auto & raw : lines) {
        const auto s = strip(raw);
        if (s.empty()) {
            continue;
        }

        if (s.front() == '.') {
            std::istringstream ss(s);
            std::string dir, name;
            ss >> dir >> name;
            if (dir == ".entry") {
                entry_label = name;
                has_entry   = true;
            } else if (dir == ".func") {
                std::uint32_t nlocals = 0, nargs = 0;
                if (!(ss >> nlocals >> nargs)) {
                    return std::unexpected(std::errc::invalid_argument);
                }
                if (!func_ids.emplace(name, static_cast< std::uint32_t >(out.funcs.size())).second) {
                    return std::unexpected(std::errc::invalid_argument);
                }
                out.funcs.push_back({
                    .addr    = addr,
                    .nlocals = nlocals,
                    .nargs   = nargs
                });
            } else {
                return std::unexpected(std::errc::invalid_argument);
            }
            continue;
        }
        if (s.back() == ':') {
            const auto label = strip(s.substr(0, s.size() - 1));
            if (!labels.emplace(label, addr).second) {
                return std::unexpected(std::errc::invalid_argument);
            }
            continue;
        }
        ++addr;
    }

    if (has_entry) {
        const auto it = labels.find(entry_label);
        if (it == labels.end()) {
            return std::unexpected(std::errc::invalid_argument);
        }
        out.entry = it->second;
    }

    for (const auto & raw : lines) {
        const auto s = strip(raw);
        if (s.empty() || s.front() == '.' || s.back() == ':') {
            continue;
        }

        std::istringstream ss(s);
        std::string name, operand, extra;
        ss >> name >> operand >> extra;
        if (!extra.empty()) {
            return std::unexpected(std::errc::invalid_argument);
        }

        const auto * mn = std::find_if(std::begin(mnemonics), std::end(mnemonics), [&name](const auto & m)
        {
            return m.name == name;
        });
        if (mn == std::end(mnemonics)) {
            return std::unexpected(std::errc::invalid_argument);
        }
        if (mn->needs_operand != !operand.empty()) {
            return std::unexpected(std::errc::invalid_argument);
        }

        std::uint32_t value = 0;
        if (mn->needs_operand) {
            std::uint32_t number = 0;
            const auto [ptr, ec] = std::from_chars(operand.data(), operand.data() + operand.size(), number);
            if (ec == std::errc{} && ptr == operand.data() + operand.size()) {
                value = number;
            } else if (mn->code == hhvm::op_call) {
                const auto it = func_ids.find(operand);
                if (it == func_ids.end()) {
                    return std::unexpected(std::errc::invalid_argument);
                }
                value = it->second;
            } else {
                const auto it = labels.find(operand);
                if (it == labels.end()) return std::unexpected(std::errc::invalid_argument);
                value = it->second;
            }
        }
        out.ops.push_back({ mn->code, value });
    }
    return out;
}
}

std::expected< hhvm::image, std::errc >
hhvm::image::from(const fs::path & _path) noexcept
{
    std::ifstream file(_path, std::ios::binary);
    if (!file) {
        return std::unexpected(std::errc::no_such_file_or_directory);
    }

    auto assemble_rc = assemble_text(file);
    if (!assemble_rc.has_value()) {
        return std::unexpected(assemble_rc.error());
    }
    auto assemble = assemble_rc.value();

    std::unique_ptr< op[] > ops_guard(new (std::nothrow) op[assemble.ops.size()]);
    if (!ops_guard) {
        return std::unexpected(std::errc::not_enough_memory);
    }
    std::copy(assemble.ops.begin(), assemble.ops.end(), ops_guard.get());

    std::unique_ptr< func[] > funcs_guard(new (std::nothrow) func[assemble.funcs.size()]);
    if (!funcs_guard) {
        return std::unexpected(std::errc::not_enough_memory);
    }
    std::copy(assemble.funcs.begin(), assemble.funcs.end(), funcs_guard.get());

    return image({
        .entry     = assemble.entry,
        .ops       = ops_guard.release(),
        .ops_len   = static_cast< std::uint32_t >(assemble.ops.size()),
        .funcs     = funcs_guard.release(),
        .funcs_len = static_cast< std::uint32_t >(assemble.funcs.size()),
    });
}

std::expected< hhvm::image, std::errc >
hhvm::image::from(std::uint32_t _entry, std::initializer_list< op > _ops, std::initializer_list< func > _funcs) noexcept
{
    std::unique_ptr< op[] > ops_guard(new (std::nothrow) op[_ops.size()]);
    if (!ops_guard) {
        return std::unexpected(std::errc::not_enough_memory);
    }
    std::copy(_ops.begin(), _ops.end(), ops_guard.get());

    std::unique_ptr< func[] > funcs_guard(new (std::nothrow) func[_funcs.size()]);
    if (!funcs_guard) {
        return std::unexpected(std::errc::not_enough_memory);
    }
    std::copy(_funcs.begin(), _funcs.end(), funcs_guard.get());

    return image({
        .entry     = _entry,
        .ops       = ops_guard.release(),
        .ops_len   = static_cast< std::uint32_t >(_ops.size()),
        .funcs     = funcs_guard.release(),
        .funcs_len = static_cast< std::uint32_t >(_funcs.size()),
    });
}

hhvm::image::~image() noexcept
{
    delete [] inside_.ops;
    delete [] inside_.funcs;
}

hhvm::image::image(image && _rhs) noexcept
    : inside_(std::exchange(_rhs.inside_, {}))
{
}

hhvm::image &
hhvm::image::operator=(image && _rhs) noexcept
{
    if (std::addressof(_rhs) == this) {
        return * this;
    }

    delete[] inside_.ops;
    delete[] inside_.funcs;

    inside_ = std::exchange(_rhs.inside_, {});

    return * this;
}

hhvm::image::image(image_inside && _inside) noexcept
    : inside_(std::move(_inside))
{
}
