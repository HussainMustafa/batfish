package(default_visibility = ["//visibility:public"])

java_library(
    name = "cumulus_ports",
    srcs = [
        ":CumulusPortsLexer.java",
        ":CumulusPortsParser.java",
        ":CumulusPortsParserBaseListener.java",
        ":CumulusPortsParserListener.java",
    ],
    deps = [
        "//projects/batfish/src/main/java/org/batfish/grammar/cumulus_ports/parsing:cumulus_ports_base",
        "@maven//:org_antlr_antlr4_runtime",
    ],
)

genrule(
    name = "cumulus_ports_generated",
    srcs = glob([
        "*.g4",
    ]),
    outs = [
        "CumulusPortsLexer.java",
        "CumulusPortsParser.java",
        "CumulusPortsParserBaseListener.java",
        "CumulusPortsParserListener.java",
    ],
    cmd = """
java -cp $(location @antlr4_tool//jar) \
    org.antlr.v4.Tool \
    -Xexact-output-dir \
       $(location CumulusPortsLexer.g4) \
       $(location CumulusPortsParser.g4) \
    -package org.batfish.grammar.cumulus_ports \
    -encoding UTF-8 \
    -Werror \
    -o $(@D)""",
    tools = ["@antlr4_tool//jar"],
)
