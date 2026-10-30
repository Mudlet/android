cmake_minimum_required(VERSION 3.16)
project(hunspell CXX)
file(GLOB HUNSPELL_SOURCES src/hunspell/*.cxx)
add_library(hunspell STATIC ${HUNSPELL_SOURCES})
target_compile_features(hunspell PRIVATE cxx_std_11)
target_compile_definitions(hunspell PRIVATE BUILDING_LIBHUNSPELL)
install(TARGETS hunspell ARCHIVE DESTINATION lib)
install(FILES src/hunspell/atypes.hxx src/hunspell/hunspell.h src/hunspell/hunspell.hxx src/hunspell/hunvisapi.h
              src/hunspell/w_char.hxx
        DESTINATION include/hunspell)
