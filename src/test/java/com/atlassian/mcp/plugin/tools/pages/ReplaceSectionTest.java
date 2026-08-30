package com.atlassian.mcp.plugin.tools.pages;

import static org.junit.Assert.*;

import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.StorageToMarkdown;
import org.junit.Test;

/**
 * A page's headings have to survive the round trip into Markdown with their level still readable,
 * because that is the only thing {@code replace_section} has to find a section by. Flexmark writes
 * h1 and h2 as setext by default — the title underlined rather than prefixed — which made the tool
 * blind to the two levels Confluence pages use most.
 */
public class ReplaceSectionTest {

  @Test
  public void everyHeadingLevelConvertsToAnAtxHeading() {
    for (int level = 1; level <= 6; level++) {
      String storage = "<h" + level + ">Section</h" + level + "><p>body</p>";
      String markdown = StorageToMarkdown.convert(storage);
      assertTrue(
          "h" + level + " must convert to an ATX heading, got: " + markdown,
          markdown.contains("#".repeat(level) + " Section"));
    }
  }

  @Test
  public void replacesTheSectionAtEveryHeadingLevel() throws Exception {
    for (int level = 1; level <= 6; level++) {
      String markdown =
          StorageToMarkdown.convert(
              "<h" + level + ">Section</h" + level + "><p>old body</p><hr/><p>tail</p>");

      String updated = ReplaceSectionTool.replaceSection(markdown, "Section", -1, "new body");

      assertTrue("level " + level + " kept its heading", updated.contains("Section"));
      assertTrue("level " + level + " took the new body", updated.contains("new body"));
      assertFalse("level " + level + " dropped the old body", updated.contains("old body"));
    }
  }

  @Test
  public void replacesOnlyTheNamedSection() throws Exception {
    String markdown =
        StorageToMarkdown.convert(
            "<h2>First</h2><p>keep first</p><h2>Second</h2><p>replace me</p>"
                + "<h2>Third</h2><p>keep third</p>");

    String updated = ReplaceSectionTool.replaceSection(markdown, "Second", -1, "new body");

    assertTrue(updated.contains("keep first"));
    assertTrue(updated.contains("new body"));
    assertFalse(updated.contains("replace me"));
    assertTrue(updated.contains("keep third"));
  }

  @Test
  public void aSectionRunsUntilAHeadingOfEqualOrHigherLevel() throws Exception {
    String markdown =
        StorageToMarkdown.convert(
            "<h2>Parent</h2><p>replace me</p><h3>Child</h3><p>also replaced</p>"
                + "<h2>Next</h2><p>keep</p>");

    String updated = ReplaceSectionTool.replaceSection(markdown, "Parent", -1, "new body");

    assertFalse("a nested section belongs to its parent", updated.contains("also replaced"));
    assertFalse(updated.contains("replace me"));
    assertTrue(updated.contains("keep"));
  }

  @Test
  public void headingLevelDisambiguatesARepeatedTitle() throws Exception {
    String markdown =
        StorageToMarkdown.convert(
            "<h2>Notes</h2><p>section two</p><h3>Notes</h3><p>section three</p>");

    String updated = ReplaceSectionTool.replaceSection(markdown, "Notes", 3, "new body");

    assertTrue("the h2 section is untouched", updated.contains("section two"));
    assertFalse(updated.contains("section three"));
  }

  @Test
  public void aRepeatedTitleWithoutALevelIsRefused() {
    String markdown =
        StorageToMarkdown.convert("<h2>Notes</h2><p>two</p><h3>Notes</h3><p>three</p>");

    McpToolException e =
        assertThrows(
            McpToolException.class,
            () -> ReplaceSectionTool.replaceSection(markdown, "Notes", -1, "new body"));
    assertTrue(e.getMessage(), e.getMessage().contains("heading_level"));
  }

  @Test
  public void anAbsentHeadingIsReportedWithTheOnesThatExist() {
    String markdown = StorageToMarkdown.convert("<h2>Present</h2><p>body</p>");

    McpToolException e =
        assertThrows(
            McpToolException.class,
            () -> ReplaceSectionTool.replaceSection(markdown, "Missing", -1, "new body"));
    assertTrue(e.getMessage(), e.getMessage().contains("Present"));
  }
}
