# Minimal CMake support for micro-ecc: the library, plus its tests when this is
# the top-level project or UECC_BUILD_TESTS is ON.
#
#   cmake -B build && cmake --build build && ctest --test-dir build
#
# A parent project (for example gr-m17) can run the same tests with:
#   set(UECC_BUILD_TESTS ON)
#   add_subdirectory(micro-ecc ${CMAKE_CURRENT_BINARY_DIR}/micro-ecc)
cmake_minimum_required(VERSION 3.10...3.28)
project(micro-ecc C)

add_library(uecc STATIC uECC.c)
target_include_directories(uecc PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    set(_uecc_tests_default ON)
else()
    set(_uecc_tests_default OFF)
endif()
option(UECC_BUILD_TESTS "Build and register the micro-ecc tests" ${_uecc_tests_default})

if(UECC_BUILD_TESTS)
    enable_testing()

    # Each test is built against its own copy of uECC.c so it can use its own
    # compile definitions. Exit codes: 0 pass, 1 fail, 77 skipped (no curve).
    function(uecc_add_test name source)
        add_executable(${name} ${CMAKE_CURRENT_SOURCE_DIR}/test/${source} ${CMAKE_CURRENT_SOURCE_DIR}/uECC.c)
        target_include_directories(${name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
        if(ARGN)
            target_compile_definitions(${name} PRIVATE ${ARGN})
        endif()
        add_test(NAME ${name} COMMAND ${name})
        set_tests_properties(${name} PROPERTIES SKIP_RETURN_CODE 77)
    endfunction()

    set(_uecc_tests
        kat_secp256r1           test_kat_secp256r1.c
        public_key_vectors      public_key_test_vectors.c
        ecdsa_vectors           ecdsa_test_vectors.c
        ecdsa                   test_ecdsa.c
        ecdh                    test_ecdh.c
        compute                 test_compute.c
        compress                test_compress.c
        presign                 test_presign.c
    )

    list(LENGTH _uecc_tests _n)
    math(EXPR _last "${_n} - 1")
    foreach(_i RANGE 0 ${_last} 2)
        math(EXPR _j "${_i} + 1")
        list(GET _uecc_tests ${_i} _name)
        list(GET _uecc_tests ${_j} _src)

        # Default configuration for this host.
        uecc_add_test(uecc_${_name} ${_src})

        # On 64-bit hosts, also run everything with 32-bit words, which turns on
        # the comb multiplication path used on the STM32/GD32 targets.
        if(CMAKE_SIZEOF_VOID_P EQUAL 8)
            uecc_add_test(uecc_${_name}_w32 ${_src} uECC_WORD_SIZE=4)
        endif()
    endforeach()
endif()
