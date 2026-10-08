package com.binitech.elosys.adapters.outbound.json;

import com.binitech.elosys.application.ports.outbound.LexiconPort;
import com.binitech.elosys.domain.social.Lexicon;
import com.binitech.elosys.domain.social.Lexicon.Term;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ClasspathLexiconAdapter implements LexiconPort {
  private final Lexicon lexicon;

  public ClasspathLexiconAdapter() {
    try (InputStream in = new ClassPathResource("social/lexicon.json").getInputStream()) {
      JsonNode root = JsonMapper.builder().build().readTree(in);
      List<Term> terms = new ArrayList<>();
      for (JsonNode category : root.get("categories")) {
        String name = category.get("category").asString();
        for (JsonNode t : category.get("terms")) {
          JsonNode note = t.get("note");
          terms.add(
              new Term(
                  name,
                  t.get("term").asString(),
                  t.get("weight").asString(),
                  note == null || note.isNull() ? null : note.asString()));
        }
      }
      this.lexicon = new Lexicon(terms);
    } catch (IOException e) {
      throw new UncheckedIOException("social/lexicon.json ausente ou inválido", e);
    }
  }

  @Override
  public Lexicon lexicon() {
    return lexicon;
  }
}
