package mill.main.client.universal;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Locale;

import de.tototec.cmdoption.CmdlineParser;
import de.tototec.cmdoption.CmdlineParserException;

class MillUniversalClient {

    public static void main(String[] args) {
        try {
            final int exitCode = run(args);
            System.exit(exitCode);
        } catch (Exception e) {
            System.exit(1);
        }
    }

    protected static CmdlineParser createParser(Object cmdline) {
        CmdlineParser cp = new CmdlineParser(cmdline);
        cp.setProgramName("mill");
        cp.setAboutLine("Mill build tool, version " + Versions.millVersion());
        cp.setAggregateShortOptionsWithPrefix("-");
        cp.setStopAcceptOptionsAfterParameterIsSet(true);
        return cp;
    }

    public static int run(String[] args) throws Exception {
        final Cmdline cmdline = new Cmdline();
        final CmdlineParser cp = createParser(cmdline);
        try {
            cp.parse(args);
        } catch (CmdlineParserException e) {
            System.err.println("Invalid commandline options given. " + e.getLocalizedMessage() +
                "\nUse `--help` to display all valid commandline options");
            return 1;
        }

        if (cmdline.getHelp()) {
            cp.usage();
            return 0;
        }

        if (cmdline.showVersion) {
            System.out.println(MessageFormat.format(
                "Mill Build Tool version {0}}\n" +
                    "Java version: {1}, vendor: {2}, runtime: {3}\n" +
                    "Default locale: {4}, platform encoding: {5}\n" +
                    "OS name: \"{6}\", version: {7}, arch: {8}",
                /* 0 */ Versions.millVersion(),
                /* 1 */ System.getProperty("java.version", "<unknown Java version>"),
                /* 2 */ System.getProperty("java.vendor", "<unknown Java vendor"),
                /* 3 */ System.getProperty("java.home", "<unknown runtime"),
                /* 4 */ Locale.getDefault(),
                /* 5 */ System.getProperty("file.encoding", "<unknown encoding>"),
                /* 6 */ System.getProperty("os.name", "<unknown>"),
                /* 7 */ System.getProperty("os.version", "<unkown>"),
                /* 8 */ System.getProperty("os.arch", "<unknown>")
                )
            );
            return 0;
        }

        if (cmdline.interactive || isWindows()) {
            // We need to run in interative mode,
            // so no client server mode
            // TODO: strip the first "-i" from the args
            String[] stripped = args;
            if(args.length >= 1 && (args[0] == "-i" || args[0] == "--interactive")) {
                stripped = Arrays.copyOfRange(args, 1, args.length);
            }
            return inProcessRunner.apply(args);

        } else {
            return lightweightClientRunner.apply(args);
        }

    }

    public static final boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    public static interface CheckedFunction<T, R> {
        R apply(T t) throws Exception;
    }

    // avoid dependencies loading before we need them
    private static final CheckedFunction<String[], Integer> lightweightClientRunner = args -> {
        int exitCode = mill.main.client.MillClientMain.main0(args);
        if (exitCode == mill.main.client.MillClientMain.ExitServerCodeWhenVersionMismatch()) {
            exitCode = mill.main.client.MillClientMain.main0(args);
        }
        return exitCode;
    };
    private static final CheckedFunction<String[], Integer> inProcessRunner = args -> {
        return mill.MillMain.main1(args);
    };

}
