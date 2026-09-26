#ifndef HHVM_HHVM_HPP
#define HHVM_HHVM_HPP

#include <initializer_list>
#include <system_error>
#include <filesystem>
#include <expected>
#include <memory>

namespace hhvm
{
namespace fs = std::filesystem;

class image;
class runtime;

/**
 * @brief HHVM opcodes
 */
enum opcode // not `enum class` cause op prefix
    : std::uint32_t
{
    op_nop = 0,

    op_halt  = 1,
    op_push  = 2,
    op_pop   = 3,

    // saturated operations
    op_inc = 4,
    op_dec = 5,
    op_add = 6,
    op_sub = 7,
    op_mul = 8,
    op_div = 9, // x/0 -> 0

    op_and = 10,
    op_or  = 11,
    op_xor = 12,
    op_shl = 13,
    op_shr = 14,

    op_call    = 15,
    op_ret     = 16,
    op_syscall = 17,

    op_jump          = 18,
    op_jump_if_true  = 19,
    op_jump_if_false = 20,
    op_load          = 21,
    op_store         = 22,
    op_arg           = 23,
};

/**
 * @brief HHVM image - contains instuctions set
 */
class image
{
    friend class runtime;

public:
    struct op
    {
        hhvm::opcode  opcode = hhvm::op_nop;
        std::uint32_t value  = 0;
    };

    struct func
    {
        std::uint32_t addr    = 0;
        std::uint32_t nlocals = 0;
        std::uint32_t nargs   = 0;
    };

    /**
     * @brief make image from .hhvm file
     */
    static std::expected< image, std::errc > from(const fs::path & _path) noexcept;

    /**
     * @brief make image from language structure (do not use - dev only)
     */
    static std::expected< image, std::errc > from(std::uint32_t _entry, std::initializer_list< op > _ops, std::initializer_list< func > _funcs) noexcept;

public:
    image() = delete;
    ~image() noexcept;

    image(const image &) = delete;
    image(image && _rhs) noexcept;

    image &
    operator=(const image &) = delete;
    image &
    operator=(image && _rhs) noexcept;

private:
    struct image_inside
    {
        std::uint32_t entry     = 0;
        op *          ops       = nullptr;
        std::uint32_t ops_len   = 0;
        func *        funcs     = nullptr;
        std::uint32_t funcs_len = 0;
    };

    explicit image(image_inside && _inside) noexcept;

private:
    image_inside inside_ = {};
};

/**
 * @brief HHVM runtime - execute HHVM image
 */
class runtime
{
public:
    /**
     * @brief make runtime with default stack size
     */
    static std::expected< runtime, std::errc > make() noexcept;

    /**
     * @brief execute following image
     * @return std::errc::interrupted on op_halt
     * @return std::errc::XX on other error
     * @return std::errc{} after last instuction (invalid behavior)
     */
    std::errc execute(const image & _image) noexcept;
    
public:
    runtime() = delete;
    ~runtime() noexcept;

    runtime(const runtime &) = delete;
    runtime(runtime && _rhs) noexcept;

    runtime &
    operator=(const runtime &) = delete;
    runtime &
    operator=(runtime && _rhs) noexcept;

private:
    struct runtime_inside
    {
        std::uint32_t * memory = nullptr;

        std::uint32_t ip = 0; // instruction ptr
        std::uint32_t sp = 0; // stack ptr
        std::uint32_t fp = 0; // frame ptr
    };

    explicit runtime(runtime_inside && _inside) noexcept;

    std::errc process_opcode(const image & _image, opcode _code, std::uint32_t _value) noexcept;

private:
    runtime_inside inside_ = {};
};
}

#endif
