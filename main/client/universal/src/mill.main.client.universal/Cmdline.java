package mill.main.client.universal;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import de.tototec.cmdoption.CmdOption;

public class Cmdline {

    //////////////////////////
    // Generic options

    @CmdOption(
        args = {"params"},
        description = "The main target and its options",
        maxCount = -1
    )
    /* package */ List<String> params = new LinkedList<>();

    @CmdOption(
        names = {"--help"},
        description = "Print this help message",
        isHelp = true
    )
    private boolean help = false;

    public boolean getHelp() {
        return help;
    }

    @CmdOption(
        names = {"-v", "--version"},
        description = "Show mill version and exit"
    )
    /* package */ boolean showVersion;

    @CmdOption(
        names = {"-i", "--interactive"},
        description = "Run Mill in interactive mode, suitable for opening REPLs and taking user input. " +
            "In this mode, no mill server will be used."
    )
    /* package */ boolean interactive = false;

    @CmdOption(
        names = {"-D", "--define"},
        args = {"key=value"},
        description = "Define (or overwrite) a system property",
        maxCount = -1
    )
    /* package */ void addSysProp(String define) {
        String[] split = define.split("[=]", 2);
        if (split.length == 2) {
            sysProps.put(split[0], split[1]);
        } else {
            sysProps.put(split[0], null);
        }
    }
    /* package */ Map<String, String> sysProps = new LinkedHashMap<>();

    @CmdOption(
        names = {"-k", "--keep-going"},
        description = "Continue build, even after build failures"
    )
    /* package */ boolean keepGoing = false;

    @CmdOption(
        names = {"-d", "--debug"},
        description = "Show debug output on STDOUT"
    )
    /* package */ boolean debug = false;

    @CmdOption(
        names = {"--disable-ticker"},
        description = "Disable ticker log (e.g. short-lived prints of stages and progress bars)"
    )
    /* package */ boolean disableTicker = false;


    @CmdOption(
        names = {"-w", "--watch"},
        description = "Watch and re-run your scripts when they change"
    )
    /* package */ boolean watch = false;

    //////////////////////////
    // Ammonite options

    @CmdOption(
        names = {"-s", "--silent"},
        description = "Make ivy logs go silent instead of printing though failures will still throw exception"
    )
    /* package */ boolean silent = false;

    @CmdOption(
        names = {"--color"},
        description = "Enable or disable colored output; by default colors are enabled in both REPL " +
            "and scripts if the console is interactive, and disabled otherwise"
    )
    /* package */ boolean color = false;

    @CmdOption(
        names = {"--no-default-predef"},
        description = "Disable the default predef and run Ammonite with the minimal predef possible"
    )
    /* package */ boolean noDefaultPredef = false;


    @CmdOption(
        names = {"--thin"},
        description = "Hide parts of the core of Ammonite and some of its dependencies. " +
            "By default, the core of Ammonite and all of its dependencies can be seen by users from the Ammonite session. " +
            "This option mitigates that via class loader isolation."
    )
    /* package */ boolean thin = false;

    @CmdOption(
        names = {"-p ", "--predef"},
        args = {"file"},
        description = "Lets you load your predef from a custom location, rather than the default location in your Ammonite home"
    )
    /* package */ String predef = "";

    @CmdOption(
        names = {"-h", "--home"},
        args = {"dir"},
        description = "The home directory of the REPL; where it looks for config and caches"
    )
    /* package */ String home = "";

    @CmdOption(
        names = {"-c", "--code"},
        args = {"code"},
        description = "Pass in code to be run immediately in the REPL"
    )
    /* package */ String code = "";

}
