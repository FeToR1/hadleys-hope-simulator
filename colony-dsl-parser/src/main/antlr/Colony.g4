grammar Colony;

// Colony MVP grammar for the Hadley's Hope simulator.
// The grammar defines the source syntax. Further processing happens after AST construction.

program
    : topLevelDecl* EOF
    ;

topLevelDecl
    : eventDecl
    | behaviorDecl
    ;

eventDecl
    : EVENT typeName LBRACE eventField* RBRACE
    ;

eventField
    : identifier COLON typeRef SEMI
    ;

behaviorDecl
    : BEHAVIOR identifier FOR typeName LBRACE behaviorMember* RBRACE
    ;

behaviorMember
    : enumDecl
    | paramDecl
    | stateDecl
    | ruleDecl
    ;

enumDecl
    : ENUM identifier LBRACE enumValue (COMMA enumValue)* COMMA? RBRACE
    ;

enumValue
    : identifier
    ;

paramDecl
    : PARAM identifier COLON typeRef SEMI
    ;

// MVP state must have an explicit initial value so an instance always starts
// deterministically. A later version may relax this and require a separate
// definite-initialization analysis.
stateDecl
    : STATE identifier COLON typeRef ASSIGN expression SEMI
    ;

ruleDecl
    : onRule
    | everyRule
    ;

onRule
    : ON typeName LPAREN identifier RPAREN AS identifier block
    ;

everyRule
    : EVERY durationLiteral AS identifier block
    ;

block
    : LBRACE statement* RBRACE
    ;

statement
    : letStatement
    | ifStatement
    | assignmentStatement
    | sendStatement
    | expressionStatement
    ;

letStatement
    : LET identifier (COLON typeRef)? ASSIGN expression SEMI
    ;

ifStatement
    : IF condition block elsePart?
    ;

condition
    : LET identifier ASSIGN expression
    | expression
    ;

elsePart
    : ELSE IF condition block
    | ELSE block
    ;

assignmentStatement
    : lvalue ASSIGN expression SEMI
    ;

sendStatement
    : SEND typeName recordLiteralTail TO expression SEMI
    ;

recordLiteralTail
    : LBRACE recordFieldInit (COMMA recordFieldInit)* COMMA? RBRACE
    ;

expressionStatement
    : expression SEMI
    ;

lvalue
    : identifier (DOT identifier)*
    ;

expression
    : logicalOrExpression
    ;

logicalOrExpression
    : logicalAndExpression (OR logicalAndExpression)*
    ;

logicalAndExpression
    : equalityExpression (AND equalityExpression)*
    ;

equalityExpression
    : comparisonExpression ((EQ | NEQ) comparisonExpression)*
    ;

comparisonExpression
    : additiveExpression ((LT | LE | GT | GE) additiveExpression)*
    ;

additiveExpression
    : multiplicativeExpression ((PLUS | MINUS) multiplicativeExpression)*
    ;

multiplicativeExpression
    : unaryExpression ((STAR | SLASH | PERCENT) unaryExpression)*
    ;

unaryExpression
    : (NOT | PLUS | MINUS) unaryExpression
    | postfixExpression
    ;

postfixExpression
    : primaryExpression postfixPart*
    ;

postfixPart
    : DOT identifier
    | LPAREN argumentList? RPAREN
    | LBRACK expression RBRACK
    ;

primaryExpression
    : literal
    | identifier
    | LPAREN expression RPAREN
    | recordLiteral
    ;

recordLiteral
    : typeName LBRACE recordFieldInit (COMMA recordFieldInit)* COMMA? RBRACE
    ;

recordFieldInit
    : identifier COLON expression
    ;

argumentList
    : expression (COMMA expression)*
    ;

literal
    : TRUE
    | FALSE
    | NONE
    | STRING
    | numberLiteral
    ;

numberLiteral
    : NUMBER_UNIT
    | NUMBER
    ;

durationLiteral
    : NUMBER_UNIT
    ;

typeRef
    : typeName
    | typeName LT typeRef (COMMA typeRef)* GT
    ;

typeName
    : identifier
    | BOOL_TYPE
    | INT64_TYPE
    | REAL64_TYPE
    | DURATION_TYPE
    | PROBABILITY_TYPE
    | RATE_TYPE
    | MONEY_TYPE
    | TEMPERATURE_TYPE
    | POWER_TYPE
    | ENERGY_TYPE
    | VOLUME_TYPE
    | DISTANCE_TYPE
    | SPEED_TYPE
    | HEALTH_TYPE
    | REF_TYPE
    | OPTION_TYPE
    | LIST_TYPE
    ;

identifier
    : IDENTIFIER
    ;

// ---------- Lexer ----------

BEHAVIOR       : 'behavior';
FOR            : 'for';
EVENT          : 'event';
ENUM           : 'enum';
PARAM          : 'param';
STATE          : 'state';
ON             : 'on';
EVERY          : 'every';
AS             : 'as';
LET            : 'let';
IF             : 'if';
ELSE           : 'else';
SEND           : 'send';
TO             : 'to';
TRUE           : 'true';
FALSE          : 'false';
NONE           : 'none';

BOOL_TYPE       : 'Bool';
INT64_TYPE      : 'Int64';
REAL64_TYPE     : 'Real64';
DURATION_TYPE   : 'Duration';
PROBABILITY_TYPE: 'Probability';
RATE_TYPE       : 'Rate';
MONEY_TYPE      : 'Money';
TEMPERATURE_TYPE: 'Temperature';
POWER_TYPE      : 'Power';
ENERGY_TYPE     : 'Energy';
VOLUME_TYPE     : 'Volume';
DISTANCE_TYPE   : 'Distance';
SPEED_TYPE      : 'Speed';
HEALTH_TYPE     : 'Health';
REF_TYPE        : 'Ref';
OPTION_TYPE     : 'Option';
LIST_TYPE       : 'List';

EQ             : '==';
NEQ            : '!=';
LE             : '<=';
GE             : '>=';
AND            : '&&';
OR             : '||';
ASSIGN         : '=';
LT             : '<';
GT             : '>';
NOT            : '!';
PLUS           : '+';
MINUS          : '-';
STAR           : '*';
SLASH          : '/';
PERCENT        : '%';
DOT            : '.';
COMMA          : ',';
COLON          : ':';
SEMI           : ';';
LPAREN         : '(';
RPAREN         : ')';
LBRACE         : '{';
RBRACE         : '}';
LBRACK         : '[';
RBRACK         : ']';

// A numeric literal followed immediately by a unit suffix stays one token.
// The lexer keeps the numeric value and its unit suffix together as one token.
NUMBER_UNIT
    : DIGIT+ (DOT DIGIT+)? EXPONENT? UNIT_SUFFIX
    ;

NUMBER
    : DIGIT+ (DOT DIGIT+)? EXPONENT?
    | DOT DIGIT+ EXPONENT?
    ;

STRING
    : '"' (ESCAPE | ~["\\\r\n])* '"'
    ;

IDENTIFIER
    : LETTER (LETTER | DIGIT)*
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> channel(HIDDEN)
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> channel(HIDDEN)
    ;

WS
    : [ \t\r\n]+ -> channel(HIDDEN)
    ;

fragment LETTER
    : [a-zA-Z_]
    ;

fragment DIGIT
    : [0-9]
    ;

fragment EXPONENT
    : [eE] [+-]? DIGIT+
    ;

fragment UNIT_SUFFIX
    : [a-zA-Z_µ] [a-zA-Z0-9_µ]*
    ;

fragment ESCAPE
    : '\\' ["\\/bfnrt]
    | '\\u' HEX HEX HEX HEX
    ;

fragment HEX
    : [0-9a-fA-F]
    ;
