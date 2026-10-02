@doc{
  Exercises the Rascal IntelliJ plugin end-to-end, across two modules:

  1. Basic breakpoint: open this file, click "Run in new Rascal terminal"
     above main(), set a breakpoint on the "int sum = ..." line, and type
     main(["3", "4"]) at the rascal> prompt -- execution stops there with
     a and b visible as locals.
  2. Cross-module step: with args ["3", "4"] (b != 0), step Into the
     divide(a, b) call below -- the debugger should open Helper.rsc and
     stop on its "return a / b;" line, confirming file navigation works
     across modules, not just within this one.
  3. Uncaught exception -> offending module: call main([]) (or any args
     where the second one is "0", e.g. main(["5", "0"])) -- divide(a, 0)
     throws inside Helper.rsc. Confirm the debugger/stack trace surfaces
     Helper.rsc (the actual failure site), not this module, and that
     clicking that frame opens the right file at the right line.
}
module Sanity

import IO;
import List;
import String;
import Helper;

void main(list[str] args) {
    int a = size(args) > 0 ? toInt(args[0]) : 3;
    int b = size(args) > 1 ? toInt(args[1]) : 4;
    int sum = a + b;
    println("<a> + <b> = <sum>");
    int result = divide(a, b);
    println("<a> / <b> = <result>");
}
