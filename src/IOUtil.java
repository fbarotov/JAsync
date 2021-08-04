import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class IOUtil {

    private static final String DEFAULT_DIRECTORY = "dir";

    public static Path createFile(String filename, boolean isTempFile) throws IOException {
        return createFile(DEFAULT_DIRECTORY, filename, isTempFile);
    }

    public static Path getFile(String filename) {
        // todo: some sanity check
        return Path.of(DEFAULT_DIRECTORY + File.separator + filename);
    }

    public static Path createFile(String dir, String filename, boolean isTempFile) throws IOException {
        Path dirPath = Path.of(dir);
        if (Files.notExists(dirPath)) {
            Files.createDirectory(dirPath);
        }

        Path path = Path.of(dir + File.separator + filename);
        Files.deleteIfExists(path);

        if (isTempFile) {
            Path filePath = Files.createTempFile(Path.of(dir), filename, "");
            filePath.toFile().deleteOnExit();
            return filePath;
        }

        return Files.createFile(path);
    }
}
