#ifndef HHVM_PID_HPP
#define HHVM_PID_HPP

#include <sys/types.h>

#include <system_error>
#include <expected>
#include <cstddef>

#include <hhvm/hhvm.hpp>

namespace hhvm
{
/**
 * @brief RAII pid handler
 */
class raii_pid final
{
    static constexpr std::string_view file_name = "hhvm.pid";
    static constexpr std::size_t      path_max  = 4096;

public:
    static std::expected< raii_pid, std::errc > make(const fs::path & _directory) noexcept;

public:
    raii_pid() = delete;
    ~raii_pid() noexcept;

    raii_pid(const raii_pid &) = delete;
    raii_pid(raii_pid &&) noexcept;

    raii_pid &
    operator=(const raii_pid &) = delete;
    raii_pid &
    operator=(raii_pid &&) noexcept;

private:
    explicit raii_pid(const char * _file) noexcept;

private:
    char file_[path_max] = {};
    bool owns_           = false;
};
}

#endif