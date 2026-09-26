#include <cstddef>
#include <memory>
#include <new>
#include <iostream>

#include <hhvm/hhvm.hpp>
#include "frame.hpp"

namespace
{
constexpr std::uint32_t max_stack_size = 65536;
}

std::errc
hhvm::runtime::process_opcode(const image & _image, opcode _code, std::uint32_t _value) noexcept
{
    const auto & img            = _image.inside_;
    auto & [memory, ip, sp, fp] = inside_;

    switch (_code) {
        case hhvm::op_nop: {
            return std::errc{};
        }
        case hhvm::op_halt: {
            return std::errc::interrupted;
        }

        case hhvm::op_push: {
            if (sp >= max_stack_size) {
                return std::errc::not_enough_memory;
            }
            memory[sp++] = _value;
            return std::errc{};
        }
        case hhvm::op_pop: {
            if (sp == 0) {
                return std::errc::not_enough_memory;
            }
            --sp;
            return std::errc{};
        }

        case hhvm::op_inc: {
            if (sp == 0) {
                return std::errc::not_enough_memory;
            }
            ++memory[sp - 1];
            return std::errc{};
        }
        case hhvm::op_dec: {
            if (sp == 0) {
                return std::errc::not_enough_memory;
            }
            --memory[sp - 1];
            return std::errc{};
        }

        case hhvm::op_add:
        case hhvm::op_sub:
        case hhvm::op_mul:
        case hhvm::op_div:
        case hhvm::op_and:
        case hhvm::op_or:
        case hhvm::op_xor:
        case hhvm::op_shl:
        case hhvm::op_shr: {
            if (sp < 2) {
                return std::errc::not_enough_memory;
            }
            const auto lhs = memory[--sp];
            const auto rhs = memory[--sp];
            std::uint32_t res = 0;
            switch (_code) {
                case hhvm::op_add: {
                    res = lhs + rhs;
                } break;
                case hhvm::op_sub: {
                    res = lhs - rhs;
                } break;
                case hhvm::op_mul: {
                    res = lhs * rhs;
                } break;
                case hhvm::op_div: {
                    res = (rhs == 0) ? 0 : lhs / rhs;
                } break;
                case hhvm::op_and: {
                    res = lhs & rhs;
                } break;
                case hhvm::op_or: {
                    res = lhs | rhs;
                } break;
                case hhvm::op_xor: {
                    res = lhs ^ rhs;
                } break;
                case hhvm::op_shl: {
                    res = (rhs >= 32) ? 0 : (lhs << rhs);
                } break;
                case hhvm::op_shr: {
                    res = (rhs >= 32) ? 0 : (lhs >> rhs);
                } break;
                default:
                    break;
            }
            memory[sp++] = res;
            return std::errc{};
        }

        case hhvm::op_jump: {
            if (_value >= img.ops_len) {
                return std::errc::invalid_argument;
            }
            ip = _value;
            return std::errc{};
        }
        case hhvm::op_jump_if_true:
        case hhvm::op_jump_if_false: {
            if (sp == 0) {
                return std::errc::not_enough_memory;
            }
            if (_value >= img.ops_len) {
                return std::errc::invalid_argument;
            }
            const auto cond = memory[--sp];
            const auto take = (_code == hhvm::op_jump_if_true) ? (cond != 0) : (cond == 0);
            if (take) {
                ip = _value;
            }
            return std::errc{};
        }

        case hhvm::op_call: {
            if (_value >= img.funcs_len) {
                return std::errc::invalid_argument;
            }
            const auto & f = img.funcs[_value];
            if (f.addr >= img.ops_len) {
                return std::errc::invalid_argument;
            }
            if (sp < f.nargs) {
                return std::errc::not_enough_memory;
            }
            if (sp + f.nlocals + details::frame::pre_sp_off > max_stack_size) {
                return std::errc::not_enough_memory;
            }

            frame_write(memory, sp + details::frame::pre_sp_off, details::frame{sp - f.nargs, ip, fp });
            sp += details::frame::pre_sp_off;
            fp  = sp;
            for (std::uint32_t i = 0; i < f.nlocals; ++i) {
                memory[sp++] = 0;
            }
            ip  = f.addr;
            return std::errc{};
        }
        case hhvm::op_ret: {
            if (fp == 0) {
                return std::errc::operation_not_permitted;
            }
            if (sp == 0) {
                return std::errc::not_enough_memory;
            }

            const auto fr      = details::frame_read(memory, fp);
            const auto retval  = memory[--sp];

            sp           = fr.pre_sp;
            memory[sp++] = retval;
            fp           = fr.saved_fp;
            ip           = fr.ret_ip;

            return std::errc{};
        }

        case hhvm::op_load: {
            if (fp == 0) {
                return std::errc::operation_not_permitted;
            }
            if (sp >= max_stack_size) {
                return std::errc::not_enough_memory;
            }
            memory[sp++] = memory[fp + _value];
            return std::errc{};
        }
        case hhvm::op_store: {
            if (fp == 0 || sp == 0) {
                return std::errc::not_enough_memory;
            }
            memory[fp + _value] = memory[--sp];
            return std::errc{};
        }
        case hhvm::op_arg: {
            if (fp == 0) {
                return std::errc::operation_not_permitted;
            }
            if (sp >= max_stack_size) {
                return std::errc::not_enough_memory;
            }
            memory[sp++] = memory[details::frame_read(memory, fp).pre_sp + _value];
            return std::errc{};
        }

        case hhvm::op_syscall: {
            switch (_value) {
                case 1: { // print
                    if (sp == 0) {
                        return std::errc::not_enough_memory;
                    }
                    std::cout << memory[--sp] << '\n';
                    return std::errc{};
                }
                case 2: { // read
                    if (sp >= max_stack_size) {
                        return std::errc::not_enough_memory;
                    }
                    std::uint32_t v = 0;
                    if (!(std::cin >> v)) {
                        return std::errc::io_error;
                    }
                    memory[sp++] = v;
                    return std::errc{};
                }
                default: {
                    return std::errc::function_not_supported;
                }
            }
        }

        default: {
            return std::errc::invalid_argument;
        }
    }
}

std::expected< hhvm::runtime, std::errc >
hhvm::runtime::make() noexcept
{
    auto * memory = new (std::nothrow) std::uint32_t[max_stack_size];
    if (!memory) {
        return std::unexpected(std::errc::not_enough_memory);
    }

    return runtime(runtime_inside{
        .memory = memory,
        .ip     = 0,
        .sp     = 0,
        .fp     = 0
    });
}

std::errc
hhvm::runtime::execute(const image & _image) noexcept
{
    const auto & img = _image.inside_;

    if (img.ops_len == 0) {
        return std::errc{};
    }
    if (img.entry >= img.ops_len) {
        return std::errc::invalid_argument;
    }

    inside_.ip = img.entry;
    inside_.sp = 0;
    inside_.fp = 0;

    while (inside_.ip < img.ops_len) {
        const auto [code, value] = img.ops[inside_.ip];
        ++inside_.ip;
        const auto rc = process_opcode(_image, code, value);
        if (rc != std::errc{}) {
            return rc;
        }
    }
    return std::errc{};
}

hhvm::runtime::~runtime() noexcept
{
    delete [] inside_.memory;
}

hhvm::runtime::runtime(runtime && _rhs) noexcept
    : inside_(std::exchange(_rhs.inside_, {}))
{
}

hhvm::runtime &
hhvm::runtime::operator=(runtime && _rhs) noexcept
{
    if (std::addressof(_rhs) == this) {
        return * this;
    }

    delete[] inside_.memory;

    inside_ = std::exchange(_rhs.inside_, {});

    return * this;
}

hhvm::runtime::runtime(runtime_inside && _inside) noexcept
    : inside_(std::move(_inside))
{
}

