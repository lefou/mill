package mill.main.client.universal;

import static de.tobiasroeser.lambdatest.Expect.expectEquals;
import static de.tobiasroeser.lambdatest.Expect.expectFalse;

import java.util.Arrays;

import de.tobiasroeser.lambdatest.generic.DefaultReporter;
import de.tobiasroeser.lambdatest.junit.FreeSpec;
import de.tototec.cmdoption.CmdlineParser;
import de.tototec.cmdoption.CmdlineParserException;

public class MillUniversalClientTests extends FreeSpec {
    public MillUniversalClientTests() {
        setReporter(new DefaultReporter());

        section("Cmdline parser", () -> {

            test("validate cmdline config", () -> new CmdlineParser(new Cmdline()).validate());

            test("parse -D", () -> {
                final Cmdline cmdline = new Cmdline();
                final CmdlineParser cp = MillUniversalClient.createParser(cmdline);
                intercept(CmdlineParserException.class, () -> cp.parse("-D"));

                cp.parse("-D", "KEY1=VAL1");
                expectEquals(cmdline.sysProps.get("KEY1"), "VAL1");

                cp.parse("-D", "KEY2");
                expectEquals(cmdline.sysProps.get("KEY2"), null);

                cp.parse("-D", "KEY3=");
                expectEquals(cmdline.sysProps.get("KEY3"), "");

                // override a key
                cp.parse("-D", "KEY1");
                expectEquals(cmdline.sysProps.get("KEY1"), null);

                cp.parse("-D", "KEY1=1", "-D", "KEY2=2");
                expectEquals(cmdline.sysProps.get("KEY1"), "1");
                expectEquals(cmdline.sysProps.get("KEY2"), "2");

                // allow short option aggregation
                cp.parse("-DD", "KEY1=10", "KEY2=20");
                expectEquals(cmdline.sysProps.get("KEY1"), "10");
                expectEquals(cmdline.sysProps.get("KEY2"), "20");

            });

            test("A option after the first parameter is also a parameter", () -> {
                final Cmdline cmdline = new Cmdline();
                final CmdlineParser cp = MillUniversalClient.createParser(cmdline);
                expectFalse(cmdline.debug);
                cp.parse("version", "-d");
                expectFalse(cmdline.debug);
                expectEquals(cmdline.params, Arrays.asList("version", "-d"));
            });

        });

    }
}