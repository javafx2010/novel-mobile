import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a directory into an encrypted `.jpd` container.
 *
 * Usage:  java PackJpd.java <sourceDir> <out.jpd>
 * Requires JDK 11+. No external dependencies.
 *
 * Layout (must match JpdCodec.kt):
 *   0..3    magic "JPD1"
 *   4..19   salt (16 bytes)
 *   20..31  IV (12 bytes)
 *   32..    AES-256-GCM ciphertext of the ZIP bytes.
 *
 * Key: PBKDF2WithHmacSHA1(passphrase, salt, 65536, 256).
 */
public class PackJpd {

    private static final byte[] MAGIC = {'J', 'P', 'D', '1'};
    private static final int ITERATIONS = 65536;
    private static final int KEY_BITS = 256;
    // Keep in sync with JpdCodec.kt.
    private static final String PASSPHRASE = "wild-english.audiobook.jpd.v1";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java PackJpd.java <sourceDir> <out.jpd>");
            System.exit(1);
        }
        Path source = Paths.get(args[0]).toAbsolutePath().normalize();
        Path out = Paths.get(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("源目录不存在: " + source);
        }
        if (!Files.exists(source.resolve("manifest.json"))) {
            throw new IllegalArgumentException("源目录缺少 manifest.json");
        }

        byte[] zip = zipDirectory(source);
        byte[] container = encrypt(zip);
        Files.write(out, container);
        System.out.println("已生成: " + out + " (" + container.length + " bytes, " + zip.length + " bytes 明文 ZIP)");
    }

    private static byte[] zipDirectory(Path source) throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(source)) {
            files = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (Path file : files) {
                String name = source.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC);
        out.write(salt);
        out.write(iv);
        out.write(ciphertext);
        return out.toByteArray();
    }

    private static SecretKeySpec deriveKey(byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(PASSPHRASE.toCharArray(), salt, ITERATIONS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }
}
