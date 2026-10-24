package dev.greenflagged.pypackaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The public API used from Java, as a Java consumer would: static factories, no Kotlin types. */
class JavaApiTest {

    @Test
    void versions() {
        Version v = Version.parse("1.0RC1");
        assertEquals("1.0rc1", v.toString());
        assertTrue(v.isPrerelease());
        assertEquals(PreReleaseKind.RC, v.getPre().getKind());
        assertEquals(BigInteger.ONE, v.getPre().getNumber());
        assertTrue(v.compareTo(Version.parse("1.0")) < 0);
        assertEquals(Version.parse("1.0"), Version.parse("1.0.0"));
        assertNull(Version.parseOrNull("not a version"));
        assertThrows(InvalidVersionException.class, () -> Version.parse("not a version"));
        Version built = Version.builder().epoch(1).release(2, 0).pre(PreReleaseKind.ALPHA, 3).local("ubuntu.1").build();
        assertEquals("1!2.0a3+ubuntu.1", built.toString());
        assertEquals("2", Version.canonicalize("2.0.0"));
        assertEquals("2.0.0", Version.canonicalize("2.0.0", false));
    }

    @Test
    void specifiers() {
        SpecifierSet set = SpecifierSet.parse(">=1.0,!=1.3.*,<2");
        assertTrue(set.contains("1.4"));
        assertFalse(set.contains("1.3.1"));
        assertFalse(set.contains(Version.parse("1.5a1"), false));
        assertTrue(set.contains("1.5a1", null, true));
        assertEquals(Arrays.asList("1.2"), set.filter(Arrays.asList("0.9", "1.2", "2.0")));
        assertEquals(Collections.singletonList("x-1.2"), set.filterBy(Arrays.asList("x-0.9", "x-1.2"), s -> s.substring(2)));
        assertEquals("!=1.3.*,<2,>=1.0", set.toString());
        assertTrue(SpecifierSet.parse(">2,<1").isUnsatisfiable());
        Specifier spec = Specifier.parse("~=2.2", true);
        assertEquals("~=", spec.getOperator());
        assertTrue(spec.contains("2.3b1"));
        assertTrue(SpecifierSet.EMPTY.isEmpty());
        assertEquals(5, set.and(">0").and(SpecifierSet.parse("<3")).getSize());
    }

    @Test
    void markersAndRequirements() {
        Requirement req = Requirement.parse("Requests[Socks]>=2.8.1; python_version >= '3.8' and extra == 'Socks'");
        assertEquals("Requests", req.getName());
        assertEquals(Collections.singleton("Socks"), req.getExtras());
        assertEquals(">=2.8.1", req.getSpecifier().toString());
        assertNull(req.getUrl());
        Marker marker = req.getMarker();
        assertEquals("python_version >= \"3.8\" and extra == \"socks\"", marker.toString());
        MarkerEnvironment env = MarkerEnvironment.builder().pythonVersion("3.12").extra("socks").build();
        assertTrue(marker.evaluate(env));
        assertFalse(marker.evaluate(env.toBuilder().extra("").build(), EvaluateContext.METADATA));
        assertThrows(UndefinedEnvironmentNameException.class, () -> Marker.parse("os_name == 'nt'").evaluate(env));
        InvalidRequirementException e = assertThrows(InvalidRequirementException.class, () -> Requirement.parse("name @ "));
        assertEquals("Expected URL after @\n    name @ \n           ^", e.getMessage());
        Set<String> extras = Set.of("dev");
        assertTrue(Marker.parse("'dev' in dependency_groups").evaluate(
            MarkerEnvironment.builder().dependencyGroups(extras).build(), EvaluateContext.LOCK_FILE));
    }

    @Test
    void namesAndFilenames() {
        assertEquals("foo-bar-baz", Names.canonicalize("Foo.Bar__baz"));
        assertThrows(InvalidNameException.class, () -> Names.canonicalize("-bad-", true));
        assertTrue(Names.isNormalized("foo-bar"));
        WheelFilename w = Filenames.parseWheel("Foo_Bar-1.0-2a-py2.py3-none-any.whl");
        assertEquals("foo-bar", w.getName());
        assertEquals(new BuildTag(BigInteger.TWO, "a"), w.getBuildTag());
        assertEquals(2, w.getTags().size());
        List<String> tags = Arrays.asList(w.getTags().stream().map(Tag::toString).sorted().toArray(String[]::new));
        assertEquals(Arrays.asList("py2-none-any", "py3-none-any"), tags);
        assertEquals("foo", Filenames.parseSdist("foo-1.0.tar.gz").getName());
    }
}
