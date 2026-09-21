package colony.parser

import colony.ast.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ParserIntegrationTest {
    private val parser = ColonyParserFacade()

    @Test fun validCorpusBuildsAst() {
        Files.list(Path.of("tests/valid")).use { paths ->
            paths.filter { it.toString().endsWith(".colony") }.sorted().forEach { path ->
                assertTrue(parser.parse(Files.readString(path)).declarations.isNotEmpty(), path.toString())
            }
        }
    }

    @Test fun invalidCorpusReportsSourceDiagnostics() {
        Files.list(Path.of("tests/invalid")).use { paths ->
            paths.filter { it.toString().endsWith(".colony") }.sorted().forEach { path ->
                assertFailsWith<ColonySyntaxException>(path.toString()) { parser.parse(Files.readString(path)) }
            }
        }
    }

    @Test fun allExamplesParse() {
        Files.list(Path.of("examples")).use { paths ->
            paths.filter { it.toString().endsWith(".colony") }.sorted().forEach { path ->
                assertTrue(parser.parse(Files.readString(path)).declarations.isNotEmpty(), path.toString())
            }
        }
    }

    @Test fun precedenceAndLeftAssociativitySurviveAstConstruction() {
        val declaration = parser.parse("""
            behavior Math for House {
                state value: Real64 = 10.0 - 2.0 * 3.0 + .5;
            }
        """).declarations.single() as BehaviorDecl
        val expression = (declaration.members.single() as StateDecl).initializer as BinaryExpr
        assertEquals(BinaryOperator.ADD, expression.operator)
        val subtraction = expression.left as BinaryExpr
        assertEquals(BinaryOperator.SUB, subtraction.operator)
        assertEquals(BinaryOperator.MUL, (subtraction.right as BinaryExpr).operator)
        assertEquals(".5", (expression.right as NumberLiteral).rawNumber)
    }

    @Test fun chainedElseIfAndEmptyEventsArePreserved() {
        val declaration = parser.parse("""
            event Ping {}
            behavior Control for House {
                param target: Ref<House>;
                every 1s as update {
                    if true {} else if false {} else if true {} else { send Ping {} to target; }
                }
            }
        """).declarations.last() as BehaviorDecl
        val timer = declaration.members.filterIsInstance<EveryRule>().single()
        assertEquals("s", timer.period.unit)
        var statement = timer.body.statements.single() as IfStmt
        repeat(2) { statement = (statement.elseBranch as ElseBlock).block.statements.single() as IfStmt }
        assertIs<SendStmt>((statement.elseBranch as ElseBlock).block.statements.single())
    }
}
