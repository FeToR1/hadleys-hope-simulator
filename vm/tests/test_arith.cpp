#include <iostream>
#include <format>

#include <hhvm/hhvm.hpp>
#include <hhvm/pid.hpp>

#ifndef HHVM_TESTS_DIR
#define HHVM_TESTS_DIR
#endif

int main(int argc, char ** argv)
{
    auto strerrc = [](std::errc _errc) noexcept -> std::string
    {
        auto code = std::make_error_code(_errc);
        return code.message();
    };

    auto pid = hhvm::raii_pid::make(hhvm::fs::path(HHVM_TESTS_DIR));

    auto rc_runtime = hhvm::runtime::make();
    if (!rc_runtime.has_value()) {
        std::cout << std::format("failed to make runtime: {}\n", strerrc(rc_runtime.error()));
        return 1;
    }
    auto runtime = std::move(rc_runtime.value());

    auto rc_image = hhvm::image::from(hhvm::fs::path(HHVM_TESTS_DIR) / "arith.hhvm");
    if (!rc_image.has_value()) {
        std::cout << std::format("failed to make image from file: {}\n", strerrc(rc_image.error()));
        return 1;
    }
    auto image = std::move(rc_image.value());

    auto rc_execute = runtime.execute(image);
    if (rc_execute != std::errc::interrupted) {
        std::cout << std::format("failed to execute image: {}\n", strerrc(rc_execute));
        return 1;
    }

    return 0;
}
