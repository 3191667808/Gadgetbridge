/*  Copyright (C) 2026 Freeyourgadget

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.codegen.duml;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiLintException;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiParser;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiSchema;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil;

import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.asMap;
import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.asString;
import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.requireKeys;
import static nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.YamlUtil.toLong;

/**
 * Reads the {@code -duml-cmdset} / {@code -duml-class} keys under {@code meta}
 * and the {@code -duml} block on each type out of a raw SnakeYAML document,
 * then strips them before handing the document to {@link KaitaiParser} - so
 * that parser's strict allow-lists never need to know a DUML-specific key
 * exists. This is the composition pattern described on {@link KaitaiParser}:
 * a protocol-specific layer owns and removes its own extension keys.
 */
final class DumlSchemaParser {
    private DumlSchemaParser() {
    }

    static DumlSchema parse(final String fileName, final Map<String, Object> root) {
        final Map<String, Object> metaRaw = asMap(fileName, "meta", root.get("meta"));
        requirePresent(fileName, "meta", metaRaw, "-duml-cmdset", "-duml-class");
        final int cmdSet = (int) toLong(fileName, "meta.-duml-cmdset", metaRaw.get("-duml-cmdset"));
        final String kotlinClassName = asString(fileName, "meta.-duml-class", metaRaw.get("-duml-class"));
        if (!Character.isUpperCase(kotlinClassName.charAt(0))) {
            throw new KaitaiLintException(fileName, "meta.-duml-class", "must be PascalCase: \"" + kotlinClassName + "\"");
        }
        final Map<String, Object> strippedMeta = new LinkedHashMap<>(metaRaw);
        strippedMeta.remove("-duml-cmdset");
        strippedMeta.remove("-duml-class");

        final Map<String, Object> typesRaw = asMap(fileName, "types", root.get("types"));
        final Map<String, Object> strippedTypes = new LinkedHashMap<>();
        final Map<String, DumlSchema.TypeMeta> typeMetaByTypeId = new LinkedHashMap<>();
        for (final var e : typesRaw.entrySet()) {
            final String typeId = e.getKey();
            final Map<String, Object> body = asMap(fileName, "types." + typeId, e.getValue());
            requirePresent(fileName, "types." + typeId, body, "-duml");
            final Map<String, Object> dumlRaw = asMap(fileName, "types." + typeId + ".-duml", body.get("-duml"));
            requireKeys(fileName, "types." + typeId + ".-duml", dumlRaw, Set.of("cmd", "direction", "name"), Set.of("cmd", "direction", "name"));
            final int cmd = (int) toLong(fileName, "types." + typeId + ".-duml.cmd", dumlRaw.get("cmd"));
            final String direction = asString(fileName, "types." + typeId + ".-duml.direction", dumlRaw.get("direction"));
            if (!"request".equals(direction) && !"response".equals(direction) && !"both".equals(direction)) {
                throw new KaitaiLintException(fileName, "types." + typeId + ".-duml.direction",
                        "must be \"request\", \"response\", or \"both\" (the same shape travels either direction - "
                                + "registers the one generated class under both DumlPacketType keys), got \"" + direction + "\"");
            }
            final String name = asString(fileName, "types." + typeId + ".-duml.name", dumlRaw.get("name"));
            if (!Character.isUpperCase(name.charAt(0))) {
                throw new KaitaiLintException(fileName, "types." + typeId + ".-duml.name", "must be PascalCase: \"" + name + "\"");
            }
            typeMetaByTypeId.put(typeId, new DumlSchema.TypeMeta(cmd, direction, name));

            final Map<String, Object> strippedBody = new LinkedHashMap<>(body);
            strippedBody.remove("-duml");
            strippedTypes.put(typeId, strippedBody);
        }

        final Map<String, Object> strippedRoot = new LinkedHashMap<>(root);
        strippedRoot.put("meta", strippedMeta);
        strippedRoot.put("types", strippedTypes);

        final KaitaiSchema.Schema generic = KaitaiParser.parse(fileName, strippedRoot);
        return new DumlSchema(cmdSet, kotlinClassName, generic, typeMetaByTypeId);
    }

    /**
     * Unlike {@link YamlUtil#requireKeys}, only checks presence - other keys in {@code map} are none of DUML's business here.
     */
    private static void requirePresent(final String fileName, final String where, final Map<String, Object> map, final String... keys) {
        for (final String key : keys) {
            if (!map.containsKey(key)) {
                throw new KaitaiLintException(fileName, where, "missing required key \"" + key + "\"");
            }
        }
    }
}
