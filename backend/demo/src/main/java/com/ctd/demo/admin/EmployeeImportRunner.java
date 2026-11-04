package com.ctd.demo.admin;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** One-time server-side import of the employee file, which holds plain-text starting passwords and so should
 *  never be uploaded through the browser:
 *  <pre>java -jar messenger.jar --server.port=0 --import-employees=/secure/path/employees.csv</pre>
 *  Prints how many accounts were created and which lines were skipped (with reasons, never values), then exits.
 *  Delete the file afterwards. */
@Component
@Order(100)
public class EmployeeImportRunner implements ApplicationRunner {
    static final String OPTION = "import-employees";
    private final EmployeeImport importer;
    private final ConfigurableApplicationContext context;

    public EmployeeImportRunner(EmployeeImport importer, ConfigurableApplicationContext context) {
        this.importer = importer;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption(OPTION)) return;
        int status;
        try (BufferedReader reader = Files.newBufferedReader(Path.of(args.getOptionValues(OPTION).getFirst()),
                StandardCharsets.UTF_8)) {
            System.out.println("Importing employees; hashing passwords can take a few minutes...");
            var report = importer.importCsv(reader, true, null, Integer.MAX_VALUE);
            System.out.println("Created " + report.imported() + " accounts. Skipped " + report.skipped().size() + " lines.");
            report.skipped().forEach(problem -> System.out.println("  line " + problem.line() + ": " + problem.reason()));
            status = 0;
        } catch (IllegalArgumentException exception) {
            System.out.println("Import refused: " + exception.getMessage());
            status = 2;
        }
        int code = status;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
