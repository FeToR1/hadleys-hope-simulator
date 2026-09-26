#include "frame.hpp"

hhvm::details::frame
hhvm::details::frame_read(const std::uint32_t * _memory, std::uint32_t _fp) noexcept
{
    return {
        .pre_sp   = _memory[_fp - frame::pre_sp_off],
        .ret_ip   = _memory[_fp - frame::ret_ip_off],
        .saved_fp = _memory[_fp - frame::saved_fp_off]
    };
}

void
hhvm::details::frame_write(std::uint32_t * _memory, std::uint32_t _fp, const frame & _frame) noexcept
{
    _memory[_fp - frame::pre_sp_off]   = _frame.pre_sp;
    _memory[_fp - frame::ret_ip_off]   = _frame.ret_ip;
    _memory[_fp - frame::saved_fp_off] = _frame.saved_fp;
}
