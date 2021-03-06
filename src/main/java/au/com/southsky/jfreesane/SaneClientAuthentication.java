package au.com.southsky.jfreesane;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents the authentication configuration used by SANE clients. The SANE utilities like
 * {@code scanimage} will read the {@code ~/.sane/pass} directory (if it exists), this class
 * provides an implementation of that behavior.
 *
 * <p>
 * Threadsafe.
 */
public class SaneClientAuthentication extends SanePasswordProvider {
  private static final Logger logger = Logger.getLogger(SaneClientAuthentication.class.getName());

  public static final String MARKER_MD5 = "$MD5$";

  private static final Path DEFAULT_CONFIGURATION_PATH =
      Paths.get(System.getProperty("user.home"), ".sane", "pass");

  private final List<String> resources;
  private final List<String> usernames;
  private final List<String> passwords;

  private final Reader reader;
  private final Path path;
  private AtomicBoolean isInitialized = new AtomicBoolean(false);

  public SaneClientAuthentication() {
    this(DEFAULT_CONFIGURATION_PATH, null);
  }

  public SaneClientAuthentication(String path) {
    this(Paths.get(path), null);
  }

  public SaneClientAuthentication(Path path) {
    this(path, null);
  }

  /**
   * Returns a new {@code SaneClientAuthentication} whose configuration is represented by the
   * characters supplied by the given {@link Reader}.
   */
  public SaneClientAuthentication(Reader reader) {
    this(null, reader);
  }

  private SaneClientAuthentication(Path path, Reader reader) {
    this.path = path;
    this.reader = reader;
    this.resources = new ArrayList<>(4);
    this.usernames = new ArrayList<>(4);
    this.passwords = new ArrayList<>(4);
  }

  private BufferedReader openConfig() throws IOException {
    if (path != null) {
      return Files.newBufferedReader(path);
    } else {
      return new BufferedReader(reader);
    }
  }

  private synchronized void initializeIfRequired() {
    if (isInitialized.compareAndSet(false, true)) {
      try (BufferedReader r = openConfig()) {
        String line;

        int lineNumber = 0;
        while ((line = r.readLine()) != null) {
          lineNumber++;
          ClientCredential credential = ClientCredential.fromAuthString(line);
          if (credential == null) {
            logger.log(
                Level.WARNING,
                "ignoring invalid configuration format (line {0}): {1}",
                new Object[] {lineNumber, line});
          } else {
            resources.add(credential.backend);
            usernames.add(credential.username);
            passwords.add(credential.password);
          }
        }
      } catch (IOException e) {
        logger.log(Level.WARNING, "could not read auth configuration due to IOException", e);
      }
    }
  }

  /**
   * Returns {@code true} if the configuration contains an entry for the given resource.
   */
  @Override
  public boolean canAuthenticate(String resource) {
    if (resource == null) {
      return false;
    }

    ClientCredential credential = getCredentialForResource(resource);
    return credential != null;
  }

  public ClientCredential getCredentialForResource(String rc) {
    initializeIfRequired();
    String resource = rc.contains(MARKER_MD5) ? rc.substring(0, rc.indexOf(MARKER_MD5)) : rc;

    int idx = resources.indexOf(resource);
    if (idx != -1) {
      return new ClientCredential(resources.get(idx), usernames.get(idx), passwords.get(idx));
    }

    return null;
  }

  @Override
  public String getUsername(String resource) {
    return getCredentialForResource(resource).username;
  }

  @Override
  public String getPassword(String resource) {
    return getCredentialForResource(resource).password;
  }

  /**
   * Class to hold Sane client credentials organised by backend.
   *
   * @author paul
   */
  public static class ClientCredential {
    private final String backend;
    private final String username;
    private final String password;

    protected ClientCredential(String backend, String username, String password) {
      this.backend = backend;
      this.username = username;
      this.password = password;
    }

    public static ClientCredential fromAuthString(String authString) {
      @SuppressWarnings("StringSplitter")
      String[] fields = authString.split(":");
      if (fields.length < 3) {
        return null;
      }

      return new ClientCredential(fields[2], fields[0], fields[1]);
    }

    public String getBackend() {
      return backend;
    }

    public String getUsername() {
      return username;
    }

    public String getPassword() {
      return password;
    }
  }
}
