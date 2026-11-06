# AtoM17 native code.
#   atom17: libm17 + Codec2-mod (split-no-doubles) + Codec2-mod-neural (FARGAN) + micro-ecc + tinier-aes + the M17 receiver core (libm17rx, from
#           m17_sdrsharp) + the transmit encoder (m17tx) + the JNI bridge.
#   sdalsa: SignalDeck's root ALSA helper (GPL-3.0-or-later), shipped as an asset and run through su.
cmake_minimum_required(VERSION 3.22.1)
project(atom17_native C)

set(T ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
file(GLOB LIBM17_SRC ${T}/libm17/m17.c ${T}/libm17/decode/*.c ${T}/libm17/encode/*.c ${T}/libm17/math/*.c
                     ${T}/libm17/payload/*.c ${T}/libm17/phy/*.c)
file(GLOB CODEC2_SRC ${T}/codec2-mod/src/*.c)
set(NN ${T}/codec2-mod-neural)
set(NN_SRC ${NN}/src/c2fargan.c ${NN}/src/c2f_features.c ${NN}/model/fargan_data.c ${NN}/third_party/opus/dnn/fargan.c
           ${NN}/third_party/opus/dnn/nnet.c ${NN}/third_party/opus/dnn/nnet_default.c ${NN}/third_party/opus/dnn/parse_lpcnet_weights.c)
file(GLOB M17RX_SRC ${CMAKE_CURRENT_SOURCE_DIR}/m17rx/src/*.c)

add_library(atom17 SHARED atom17_jni.c m17tx.c ${M17RX_SRC} ${LIBM17_SRC} ${CODEC2_SRC} ${T}/micro-ecc/uECC.c ${T}/tinier-aes/aes.c ${NN_SRC})
target_include_directories(atom17 PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/m17rx/include
                           ${CMAKE_CURRENT_SOURCE_DIR}/m17rx/src ${T}/libm17 ${T}/codec2-mod/inc ${T}/micro-ecc ${T}/tinier-aes
                           ${NN}/include ${NN}/src ${NN}/model ${NN}/third_party/opus ${NN}/third_party/opus/dnn
                           ${NN}/third_party/opus/celt ${NN}/third_party/opus/include)
target_compile_definitions(atom17 PRIVATE M17RX_BUILD=1 SUPPRESS_PERF_WARNINGS)
# FARGAN's inner loops: the NN sources get -O3 (NEON on arm64 and armeabi-v7a comes from the NDK defaults)
set_source_files_properties(${NN_SRC} PROPERTIES COMPILE_OPTIONS "-O3")
target_compile_options(atom17 PRIVATE -O2 -w)
target_link_libraries(atom17 m log)

add_executable(sdalsa sdalsa/sdalsa.c)
target_compile_options(sdalsa PRIVATE -O2 -Wall -Wextra)
target_link_options(sdalsa PRIVATE -s)
if(SDALSA_ASSET_DIR)
    set_target_properties(sdalsa PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${SDALSA_ASSET_DIR}/sdalsa/${ANDROID_ABI})
endif()
