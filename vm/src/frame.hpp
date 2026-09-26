#ifndef HHVM_PRIVATE_FRAME_HPP
#define HHVM_PRIVATE_FRAME_HPP

#include <cstdint>

namespace hhvm::details
{
struct frame
{
    static constexpr std::uint32_t pre_sp_off   = 3;
    static constexpr std::uint32_t ret_ip_off   = 2;
    static constexpr std::uint32_t saved_fp_off = 1;

    std::uint32_t pre_sp   = 0;
    std::uint32_t ret_ip   = 0;
    std::uint32_t saved_fp = 0;
};

frame frame_read(const std::uint32_t * _memory, std::uint32_t _fp) noexcept;
void frame_write(std::uint32_t * _memory, std::uint32_t _fp, const frame & _frame) noexcept;
}

#endif
