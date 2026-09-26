#include <sys/types.h>
#include <sys/fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <charconv>
#include <cstring>
#include <cerrno>

#include <hhvm/pid.hpp>

namespace
{
int close_fd(int _fd) noexcept
{
    int rc = -1;
    do {
        rc = ::close(_fd);
    } while (rc == -1 && errno == EINTR);
    return rc;
}

std::errc from_errno(int _errno) noexcept
{
    return static_cast< std::errc >(_errno);
}
}

hhvm::raii_pid::raii_pid(const char * _file) noexcept
{
    std::strncpy(file_, _file, path_max - 1);
    file_[path_max - 1] = '\0';
    owns_ = true;
}

hhvm::raii_pid::~raii_pid() noexcept
{
    if (owns_) {
        ::unlink(file_);
    }
}

hhvm::raii_pid::raii_pid(raii_pid && _rhs) noexcept
{
    std::memcpy(file_, _rhs.file_, path_max);
    owns_      = _rhs.owns_;
    _rhs.owns_ = false;
}

hhvm::raii_pid &
hhvm::raii_pid::operator=(raii_pid && _rhs) noexcept
{
    if (std::addressof(_rhs) == this) {
        if (owns_) {
            ::unlink(file_);
        }
        std::memcpy(file_, _rhs.file_, path_max);
        owns_      = _rhs.owns_;
        _rhs.owns_ = false;
    }
    return * this;
}

std::expected< hhvm::raii_pid, std::errc >
hhvm::raii_pid::make(const fs::path & _directory) noexcept
{
    std::error_code ec = {};
    const bool      is_dir = fs::is_directory(_directory, ec);
    if (ec)      return std::unexpected(std::errc::io_error);
    if (!is_dir) return std::unexpected(std::errc::not_a_directory);

    const auto & dir = _directory.native();
    if (dir.size() + 1 + file_name.size() + 1 > path_max) {
        return std::unexpected(std::errc::filename_too_long);
    }
    char        path[path_max] = {};
    std::size_t pos            = 0;
    if (!dir.empty()) {
        std::memcpy(path, dir.data(), dir.size());
        pos = dir.size();
        if (path[pos - 1] != '/') path[pos++] = '/';
    }
    std::memcpy(path + pos, file_name.data(), file_name.size());
    path[pos + file_name.size()] = '\0';

    const int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd == -1) return std::unexpected(from_errno(errno));

    const pid_t current     = ::getpid();
    char        content[16] = {};

    const auto [num_end, conv_ec] = std::to_chars(content, content + sizeof(content), static_cast< std::uint64_t >(current));

    std::size_t len = static_cast< std::size_t >(num_end - content);
    content[len++]  = '\n';

    std::errc rc        = std::errc{};
    bool      fd_closed = false;

    if (conv_ec != std::errc{}) {
        return std::unexpected(std::errc::value_too_large);
    }

    const char * p    = content;
    std::size_t  left = len;
    while (left != 0) {
        const ssize_t n = ::write(fd, p, left);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            rc = from_errno(errno);
            break;
        }
        if (n == 0) {
            rc = std::errc::io_error;
            break;
        }
        p    += static_cast< std::size_t >(n);
        left -= static_cast< std::size_t >(n);
    }

    if (rc == std::errc{}) {
        if (close_fd(fd) != 0) {
            rc = from_errno(errno);
        }
        fd_closed = true;
    }

    if (rc != std::errc{}) {
        if (!fd_closed) {
            close_fd(fd);
        }
        ::unlink(path);
        return std::unexpected(rc);
    }

    return raii_pid(path);
}
