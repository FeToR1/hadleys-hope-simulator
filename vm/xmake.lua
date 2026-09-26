set_xmakever("2.5.1")

set_languages("c++23")
set_warnings("all")

target("hhvm_core")
    set_kind("static")
    add_includedirs("include", "src", {public = true})
    add_headerfiles("include/(hhvm/*.hpp)")
    add_files("src/*.cpp")

target("hhvm")
    set_kind("binary")
    add_deps("hhvm_core")
    add_files("main.cpp")

local test_outputs = {
    test_arith     = "6\n42\n7\n0\n8\n14\n6\n16\n1\n0\n6\n4",
    test_factorial = "120",
    test_loop      = "15",
    test_nested    = "116",
    test_sum       = "5"
}

for _, file in ipairs(os.files("tests/test_*.cpp")) do
    local name = path.basename(file)
    target(name)
        set_kind("binary")
        set_default(false)
        set_group("tests")
        add_deps("hhvm_core")
        add_files(file)

        add_defines(string.format("HHVM_TESTS_DIR=\"%s\"", path.join(os.projectdir(), "tests")))
        add_tests("run", {trim_output = true, pass_outputs = {test_outputs[name] or "OK"}})
end
