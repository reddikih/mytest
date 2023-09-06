import static java.lang.String.format;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReleaseNoteCreation {

    private final String DEBUG = System.getenv("DEBUG");
    private final String MERGED_STATE = "merged";

    private String owner = "scalar-labs";
    private String projectTitlePrefix = "ScalarDB";

    private Map<Category, List<RnInfo>> categoryMap = new HashMap<>();
    private Map<String, List<RnInfo>> sameAsItems = new HashMap<>();

    /**
     * Usage example:
     * java --source 11 ReleaseNoteCreation.java scalar-labs ScalarDB 4.0.0 scalardb
     */
    public static void main(String... args) throws Exception {
        if (args.length != 4) {
            System.err.printf(
                "Usage: java --source 11 %s.java owner pjprefix version repository\n",
                ReleaseNoteCreation.class.getSimpleName());
        }

        String owner = args[0];
        String projectTitlePrefix = args[1];
        String version = args[2];
        String repository = args[3];

        ReleaseNoteCreation ghSample = new ReleaseNoteCreation(owner, projectTitlePrefix);
        ghSample.createReleaseNote(version, repository);
    }

    public ReleaseNoteCreation(String owner, String projectTitlePrefix) {
        if (owner != null && owner.length() > 0) this.owner = owner;
        if (projectTitlePrefix != null && projectTitlePrefix.length() > 0) this.projectTitlePrefix = projectTitlePrefix;
    }

    void createReleaseNote(String version, String repository) throws Exception {
        String projectId = getProjectId(version);
        List<String> prNumbers = getPullRequestNumbers(projectId, repository);
              
        for (String prNum : prNumbers) {
            extractReleaseNoteInfo(prNum, repository);
        }
        distributeSameAsItems();
        outputReleaseNote();
    }

    String getProjectId(String version) throws Exception {
        BufferedReader br = runSubProcessAndGetOutputAsReader(
            format("gh project list --owner %s | awk '/%s/ {print}' | awk '/%s/ {print $1}'", this.owner, this.projectTitlePrefix, version));

        String line = br.readLine(); // We can assume only one line exists.
        if (line == null) throw new RuntimeException("Couldn't get the projectId");
        return line;
    }

    List<String> getPullRequestNumbers(String projectId, String repository) throws Exception {
        BufferedReader br = runSubProcessAndGetOutputAsReader(
            format("gh project item-list %s --owner %s --limit 200 | awk -F'\\t' '/%s\\t/ {print $3}'", projectId, this.owner, repository));

        String line = null;
        List<String> prNumbers = new ArrayList<>();
        while((line = br.readLine()) != null) {
            prNumbers.add(line);
        }
        return prNumbers;
    }

    String getPrState(String prNumber, String repository) throws Exception {
        BufferedReader br = runSubProcessAndGetOutputAsReader(
            format("gh pr view %s --repo %s/%s --jq \".state\" --json state", prNumber, this.owner, repository));

        String line = br.readLine(); // We can assume only one line exists.
        if (line == null) throw new RuntimeException("Couldn't get the projectId");
        return line;
    }

    Category getCategory(String prNumber, String repository) throws Exception {
        BufferedReader br = runSubProcessAndGetOutputAsReader(
            format("gh pr view %s --repo %s/%s --jq \".labels[].name\" --json labels", prNumber, this.owner, repository));

        String line = null;
        while((line = br.readLine()) != null) {
            if (isValidCategory(line)) break;
        }
        if (line == null || line.isEmpty())
            line = Category.MISCELLANEOUS.name().toLowerCase();
        return Category.getByName(line);
    }

    boolean isValidCategory(String category) {
        return Arrays.stream(Category.values())
            .anyMatch(target -> {return target.name().toLowerCase().equals(category.toLowerCase());});
    }

    void extractReleaseNoteInfo(String prNumber, String repository) throws Exception {
        String state = getPrState(prNumber, repository);
        if (!state.equalsIgnoreCase(MERGED_STATE)) return;

        RnInfo releaseNoteInfo = new RnInfo();
        Category category = getCategory(prNumber, repository);

        BufferedReader br = runSubProcessAndGetOutputAsReader(
           format("gh pr view %s --repo %s/%s --jq \".body\" --json body", prNumber, this.owner, repository));
        String line = null;
        List<String> body = new ArrayList<>();
        while((line = br.readLine()) != null) {
            if (Pattern.matches("^## *[Rr]elease *[Nn]otes? *", line)) {
                releaseNoteInfo.category = category;
                releaseNoteInfo.prNumbers.add(prNumber);

                while((line = br.readLine()) != null) {
                    if (Pattern.matches("^## *.*", line)) break; // Reached to the next section header (ended release note section)
                    if (Pattern.matches("^ *-? *N/?A *$", line)) return; // This PR is not user-facing

                    Matcher m = Pattern.compile("^ *-? *(\\p{Print}+)$").matcher(line); // Extract Release note text
                    if (m.matches()) {
                        if (!Pattern.matches("^ *-? *[Ss]ame ?[Aa]s +#?([0-9]+) *$", line)) {
                            String matched = m.group(1);
                            if (DEBUG != null) System.err.printf("matched: %s\n", matched);
                            releaseNoteInfo.releaseNoteText = m.group(1);
                        }
                    }

                    m = Pattern.compile("^ *-? *[Ss]ame ?[Aa]s +#?([0-9]+) *$").matcher(line);  // It has a related PR
                    if (m.matches()) {
                        String topicPrNumber = m.group(1);
                        if (DEBUG != null)
                            System.err.printf("PR:%s sameAs:%s\n", releaseNoteInfo.prNumbers.get(0), topicPrNumber);
                        List<RnInfo> relates = sameAsItems.get(topicPrNumber);
                        if (relates == null) relates = new ArrayList<>();
                        relates.add(releaseNoteInfo);
                        sameAsItems.put(topicPrNumber, relates);
                    }
                }
                distributeAReleaseNoteInfo(releaseNoteInfo);
            }
        }
    }

    void distributeAReleaseNoteInfo(RnInfo rnInfo) throws Exception {
        checkAReleaseNoteFormat(rnInfo);
        Arrays.stream(Category.values()).forEach(category -> {
            if (rnInfo.category.equals(category)) {
                List<RnInfo> releaseNotes = categoryMap.get(category);
                if (releaseNotes == null) releaseNotes = new ArrayList<>();
                if (!isContainedInSameAsItems(rnInfo))
                    releaseNotes.add(rnInfo);
                categoryMap.put(category, releaseNotes);
            }
        });
    }

    void distributeSameAsItems() {
        for (Entry<String, List<RnInfo>> entry : sameAsItems.entrySet()) {
            String topicPrNumber = entry.getKey();
            List<RnInfo> rnInfos = entry.getValue();

            Arrays.stream(Category.values()).forEach(category -> {
                List<RnInfo> releaseNotes = categoryMap.get(category);
                if (releaseNotes != null) {
                    releaseNotes.forEach(rnInfo -> {
                        if (rnInfo.prNumbers.get(0).equals(topicPrNumber)) {
                            rnInfos.forEach(from -> {
                                merge(from, rnInfo);
                            });
                        }
                    });
                }
            });
        }
    }

    void merge(RnInfo from, RnInfo to) {
        if (from.releaseNoteText != null && from.releaseNoteText.length() > 0) {
            to.releaseNoteText = to.releaseNoteText + " " + from.releaseNoteText;
        }
        if (DEBUG != null) System.err.printf("merged RN text: %s\n", to.releaseNoteText);
        to.prNumbers.addAll(from.prNumbers);
    }

    void outputReleaseNote() {
        StringBuilder builder = new StringBuilder();
        builder.append("## Summary\n\n");

        Arrays.stream(Category.values()).forEach(category -> {
            List<RnInfo> releaseNotes = categoryMap.get(category);
            if (releaseNotes != null && !releaseNotes.isEmpty()) {
                builder.append(String.format("## %s\n", category.getDisplayName()));
                builder.append(getFormattedReleaseNotes(category, releaseNotes)).append("\n");
            }
        });
        System.out.println(builder.toString());
    }

    String getFormattedReleaseNotes(Category category, List<RnInfo> releaseNotes) {
        StringBuilder builder = new StringBuilder();
        releaseNotes.forEach(rnInfo -> {
            builder.append(String.format("- %s (", rnInfo.releaseNoteText));
            rnInfo.prNumbers.forEach(prNum -> {builder.append(String.format("#%s ", prNum));});
            builder.deleteCharAt(builder.length() - 1); // delete the last space character
            builder.append(")\n");
        });
        return builder.toString();
    }

    BufferedReader runSubProcessAndGetOutputAsReader(String command) throws Exception {
        if (DEBUG != null) System.err.printf("Executed: %s\n", command);
        Process p = new ProcessBuilder("bash", "-c", command).start();
        p.waitFor();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        return br;
    }

    void checkAReleaseNoteFormat(RnInfo rnInfo) {
        if (rnInfo.category == null) {
            rnInfo.category = Category.MISCELLANEOUS;
            rnInfo.releaseNoteText = 
                rnInfo.releaseNoteText == null ? "[Category is null] " : "[Category is null] " + rnInfo.releaseNoteText;
        }
    }

    boolean isContainedInSameAsItems(RnInfo rnInfo) {
        return sameAsItems.values().stream().anyMatch(items -> {
                return items.contains(rnInfo);
            });
    }
}

public class RnInfo {
    public Category category;
    public String releaseNoteText;
    public List<String> prNumbers = new ArrayList<>();
}

public enum Category {
    ENHANCEMENT("Enhancements"),
    IMPROVEMENT("Improvements"),
    BUGFIX("Bug fixes"),
    DOCUMENTATION("Documentation"),
    MISCELLANEOUS("Miscellaneous");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public static Category getByName(String name) {
        return Arrays.stream(Category.values())
                .filter(v -> v.name().toLowerCase().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid name: " + name));
    }

    public static Category getByDisplayName(String displayName) {
        return Arrays.stream(Category.values())
                .filter(v -> v.displayName.equals(displayName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid displayName: " + displayName));
    }
}
