/*
 * Copyright (c) 2015 The Polymer Project Authors. All rights reserved.
 * This code may only be used under the BSD style license found at http://polymer.github.io/LICENSE
 * The complete set of authors may be found at http://polymer.github.io/AUTHORS
 * The complete set of contributors may be found at http://polymer.github.io/CONTRIBUTORS
 * Code distributed by Google as part of the polymer project is also
 * subject to an additional IP rights grant found at http://polymer.github.io/PATENTS
 */

package com.google.polymer;

import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.polymer.PolymerDatabindingLexer.Token;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Document.OutputSettings;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities.EscapeMode;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates HTML document renaming.
 */
public class HtmlRenamer {

  private static class DatabindingRenamer implements NodeVisitor {

    private final ImmutableMap<String, String> renameMap;
    private final RenameMode renameMode;

    /** true if we are inside a script element. */
    private boolean insideScriptElement = false;

    /**
     * Constructs the DatabindingRenamer to rename according to |renameMap|.
     * @param renameMap A mapping from symbol to renamed symbol.
     */
    public DatabindingRenamer(
        ImmutableMap<String, String> renameMap, RenameMode renameMode) {
      this.renameMap = renameMap;
      this.renameMode = renameMode;
    }

    @Override
    public void head(Node node, int depth) {
      if (node instanceof Element) {
        Element element = (Element) node;
        String tagName = element.tag().getName();
        if (tagName.equals("polymer-element")) {
          renameAttributesAttributeValue(element);
        } else if (tagName.equals("script")) {
          insideScriptElement = true;
        } else {
          renameAllAttributeValues(element);
        }
      } else if (node instanceof TextNode) {
        TextNode textNode = (TextNode) node;
        textNode.text(renameStringWithDatabindingDirectives(textNode.getWholeText()));
      } else if (insideScriptElement && node instanceof DataNode) {
        DataNode dataNode = (DataNode) node;
        dataNode.setWholeData(JsRenamer.renameProperties(renameMap, dataNode.getWholeData()));
      }
    }

    @Override
    public void tail(Node node, int depth) {
      if (node instanceof Element) {
        Element element = (Element) node;
        if (element.tag().equals("script")) {
          insideScriptElement = false;
        }
      }
    }

    private void renameAttributesAttributeValue(Element element) {
      String attributesValue = element.attr("attributes");
      boolean modified = false;
      String properties[] = attributesValue.split(" ");
      for (int i = 0; i < properties.length; i++) {
        String property = properties[i];
        if (renameMap.containsKey(property)) {
          properties[i] = renameMap.get(property);
          modified = true;
        }
      }
      if (modified) {
        element.attr("attributes", Joiner.on(" ").join(properties));
      }
    }

    private void renameAllAttributeValues(Element element) {
      Attributes attributes = element.attributes();
      if (attributes != null) {
        for (Attribute attribute : attributes) {
          attribute.setValue(renameStringWithDatabindingDirectives(attribute.getValue()));
        }
      }
    }

    private String renameStringWithDatabindingDirectives(String input) {
      Token tokens[] = PolymerDatabindingLexer.lex(input);
      StringBuilder sb = new StringBuilder();
      boolean insideBraces = false;
      for (Token t : tokens) {
        switch (t.type) {
          case STRING:
            if (insideBraces) {
              sb.append(JsRenamer.renamePolymerJsExpression(renameMap, t.value));
            } else {
              sb.append(t.value);
            }
            break;
          case OPENCURLYBRACES:
            insideBraces = true;
            sb.append(t.value);
            break;
          case CLOSECURLYBRACES:
            insideBraces = false;
            sb.append(t.value);
            break;
          case OPENSQUAREBRACES:
            if (renameMode == RenameMode.POLYMER_0_8) {
              insideBraces = true;
            }
            sb.append(t.value);
            break;
          case CLOSESQUAREBRACES:
            if (renameMode == RenameMode.POLYMER_0_8) {
              insideBraces = false;
            }
            sb.append(t.value);
            break;
        }
      }
      return sb.toString();
    }
  }

  public static String rename(ImmutableMap<String, String> renameMap, String htmlString) {
    Document document = Parser.parse(htmlString, "");
    OutputSettings outputSettings = document.outputSettings();
    outputSettings.prettyPrint(false);
    outputSettings.escapeMode(EscapeMode.xhtml);
    RenameMode renameMode = RenameMode.POLYMER_0_8;
    Elements polymerDomElements = document.getElementsByTag("dom-module");
    if (polymerDomElements.isEmpty()) {
      renameMode = RenameMode.POLYMER_0_5;
      polymerDomElements = document.getElementsByTag("polymer-element");
    }
    List<String> polymerCustomElements = new ArrayList<String>();
    NodeTraversor polymerDomElementTraversor =
        new NodeTraversor(new DatabindingRenamer(renameMap, renameMode));
    for (Element polymerDomElement : polymerDomElements) {
      if (renameMode == RenameMode.POLYMER_0_8) {
        String polymerElementTagName = polymerDomElement.attr("name");
        if (!polymerElementTagName.isEmpty()) {
          polymerCustomElements.add(polymerElementTagName);
        }
      }
      polymerDomElementTraversor.traverse(polymerDomElement);
    }

    for (String polymerElementTagName : polymerCustomElements) {
      Elements customElements = document.getElementsByTag(polymerElementTagName);
      for (Element customElement : customElements) {
        Attributes attributes = customElement.attributes();
        for (Attribute attribute : attributes) {
          String renamedProperty = renameMap.get(
              CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, attribute.getKey()));
          if (renamedProperty != null) {
            attribute.setKey(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, renamedProperty));
          }
        }
      }
    }

    return document.toString();
  }
}
