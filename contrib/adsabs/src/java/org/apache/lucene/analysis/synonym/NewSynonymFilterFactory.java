package org.apache.lucene.analysis.synonym;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.text.ParseException;
import java.util.HashSet;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.util.*;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.Version;
import org.apache.solr.common.util.StrUtils;

/**
 * Factory for {@link SynonymFilter}.
 * <pre class="prettyprint" >
 * &lt;fieldType name="text_synonym" class="solr.TextField" positionIncrementGap="100"&gt;
 *   &lt;analyzer&gt;
 *     &lt;tokenizer class="solr.WhitespaceTokenizerFactory"/&gt;
 *     &lt;filter class="solr.SynonymFilterFactory" synonyms="synonyms.txt" 
 *             format="solr" ignoreCase="false" expand="true" 
 *             tokenizerFactory="solr.WhitespaceTokenizerFactory"/&gt;
 *   &lt;/analyzer&gt;
 * &lt;/fieldType&gt;</pre>
 * 
 * If the LUCENE-4499 gets committed, we can remove these NewSynonym... classes.
 */
public class NewSynonymFilterFactory extends TokenFilterFactory implements ResourceLoaderAware {
  private SynonymMap map;
  private boolean ignoreCase;
  
  @Override
  public TokenStream create(TokenStream input) {
    // if the fst is null, it means there's actually no synonyms... just return the original stream
    // as there is nothing to do here.
    return map.fst == null ? input : new SynonymFilter(input, map, ignoreCase);
  }
  
  //@Override
  public void inform(ResourceLoader loader) throws IOException {
    final boolean ignoreCase = getBoolean("ignoreCase", false); 
    this.ignoreCase = ignoreCase;

    String bf = args.get("builderFactory");
    SynonymBuilderFactory builder = loadBuilderFactory(loader, bf != null ? bf : SynonymBuilderFactory.class.getName());
    
    try {
      map = builder.create(loader);
    } catch (ParseException e) {
      throw new IOException(e);
    }
  }
  
  
  public static class SynonymParser extends SynonymMap.Builder {

    public SynonymParser(boolean dedup) {
      super(dedup);
    }

    public void add(Reader in) throws IOException, ParseException {
      throw new IllegalAccessError("You must override this method");
    }
  }
  
  
  public static class SynonymBuilderFactory extends TokenizerFactory implements ResourceLoaderAware {
    
    @Override
    public Tokenizer create(Reader input) {
      // TODO : this could be used to parse the source data (right now Solr and WordNet synonym
      // parser do it
      throw new IllegalAccessError("Not implemented");
    }
    
    public SynonymMap create(ResourceLoader loader) throws IOException, ParseException {
      
      String synonyms = args.get("synonyms");
      if (synonyms == null)
        throw new IllegalArgumentException("Missing required argument 'synonyms'.");
      
      CharsetDecoder decoder = IOUtils.CHARSET_UTF_8.newDecoder();
      decoder.onMalformedInput(CodingErrorAction.REPORT)
        		 .onUnmappableCharacter(CodingErrorAction.REPORT);
      
      SynonymParser parser = getParser(getAnalyzer(loader));
      
      File synonymFile = new File(synonyms);
      if (synonymFile.exists()) {
        decoder.reset();
        parser.add(new BufferedReader(new InputStreamReader(loader.openResource(synonyms), decoder)));
      } else {
        List<String> files = splitFileNames(synonyms);
        for (String file : files) {
          decoder.reset();
          parser.add(new InputStreamReader(loader.openResource(file), decoder));
        }
      }
      return parser.build();
      
    }
    
    protected Analyzer getAnalyzer(ResourceLoader loader) throws IOException {
      final boolean ignoreCase = getBoolean("ignoreCase", false); 

      String tf = args.get("tokenizerFactory");

      final TokenizerFactory factory = tf == null ? null : loadTokenizerFactory(loader, tf);
      
      return new Analyzer() {
        @Override
        protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
          Tokenizer tokenizer = factory == null ? new WhitespaceTokenizer(Version.LUCENE_40, reader) : factory.create(reader);
          TokenStream stream = ignoreCase ? new LowerCaseFilter(Version.LUCENE_40, tokenizer) : tokenizer;
          return new TokenStreamComponents(tokenizer, stream);
        }
      };
    }
    
    protected SynonymParser getParser(Analyzer analyzer) {
      
      String format = args.get("format");
      boolean expand = getBoolean("expand", true);
      
      if (format == null || format.equals("solr")) {
        // TODO: expose dedup as a parameter?
        return new NewSolrSynonymParser(true, expand, analyzer);
      } else if (format.equals("wordnet")) {
        return new NewWordnetSynonymParser(true, expand, analyzer);
      } else if (format.equals("semicolon")) {
        return new NewSemicolonSynonymParser(true, expand, analyzer);  
      } else {
        // TODO: somehow make this more pluggable
        throw new IllegalArgumentException("Unrecognized synonyms format: " + format);
      }
    }
    
    
    // (there are no tests for this functionality)
    private TokenizerFactory loadTokenizerFactory(ResourceLoader loader, String cname) throws IOException {
      TokenizerFactory tokFactory = loader.newInstance(cname, TokenizerFactory.class);
      tokFactory.setLuceneMatchVersion(luceneMatchVersion);
      tokFactory.init(args);
      if (tokFactory instanceof ResourceLoaderAware) {
        ((ResourceLoaderAware) tokFactory).inform(loader);
      }
      return tokFactory;
    }

    public void inform(ResourceLoader loader) throws IOException {
      // do nothing
    }


  }
  
  private SynonymBuilderFactory loadBuilderFactory(ResourceLoader loader, String cname) throws IOException {
    SynonymBuilderFactory builderFactory = loader.newInstance(cname, SynonymBuilderFactory.class);
    builderFactory.setLuceneMatchVersion(luceneMatchVersion);
    builderFactory.init(args);
    if (builderFactory instanceof ResourceLoaderAware) {
      ((ResourceLoaderAware) builderFactory).inform(loader);
    }
    return builderFactory;
  }
  
  
  /*
   * Various configuration options - some of the are useful for indexing, others for
   * querying only 
   */
  
  
  /*
   * Always include the source token before the synonym (this is the default, 
   * lucene behaviour)
   * 
   * "hubble space telescope was..." will be 
   * indexed as
   * 
   * 0: hubble|HST
   * 1: space
   * 2: telescope
   */
  public static class AlwaysIncludeOriginal extends SynonymBuilderFactory {
    protected SynonymParser getParser(Analyzer analyzer) {
      return new NewSolrSynonymParser(true, true, analyzer) {
        @Override
        public void add(CharsRef input, CharsRef output, boolean includeOrig) {
          super.add(input, output, true);
        }
      };
    }
  }
  
  /*
   * This parser is useful if you want to index multi-token synonyms (as one token)
   * as well as their components. Ie. "hubble space telescope was..." will be 
   * indexed as
   * 
   * 0: hubble|hubble space telescope
   * 1: space
   * 2: telescope
   * 
   * You need this behaviour for index-time synonym expansion, if you want to 
   * retain proximity queries and phrases.
   */
  public static class BestEffort extends SynonymBuilderFactory {
    protected SynonymParser getParser(Analyzer analyzer) {
      return new NewSolrSynonymParser(true, true, analyzer) {
        @Override
        public void add(CharsRef input, CharsRef output, boolean includeOrig) {
          super.add(input, replaceNulls(output), countWords(input) > 1 ? true : false);
        }
      };
    }
  }
  
  /*
   * This parser is useful if you want to index multi-token synonyms (as one token)
   * AND NOT their components. 
   * 
   * Recognize "multi\0word\0synonyms" (null bytes in the input string) 
   * but emit "multi word synonyms" in the output
   * 
   * Ie 'hubble\0space\0telescope' will be indexed as:
   * 
   * 0: hubble space telescope|hst
   * 1-3: null
   * 4: was
   */
  public static class MultiTokenReplaceNulls extends SynonymBuilderFactory {
    protected SynonymParser getParser(Analyzer analyzer) {
      return new NewSolrSynonymParser(true, true, analyzer) {
        @Override
        public void add(CharsRef input, CharsRef output, boolean includeOrig) {
          super.add(input, replaceNulls(output), includeOrig);
        }
      };
    }
  }
  
  /*
   * This is a custom configuration for multi-token query-time synonym expansion.
   * 
   * The parser searches for synonyms ignoring case, but in the output returns
   * the Original String (important for more complex tokenizer chains, ie. 
   * when synonyms should be found first, then acronyms detected)
   * 
   * The parser also returns source tokens for the multi-token group, but
   * 'eats' the source token when single-token synonym is there. 
   * 
   */
  public static class BestEffortSearchLowercase extends SynonymBuilderFactory {
  	boolean inclOrig = false;
    public void inform(ResourceLoader loader) throws IOException {
      args.put("ignoreCase", "false");
      inclOrig = args.containsKey("inclOrig") ? ((String) args.get("inclOrig")).equals("true") : false;
    }
    protected SynonymParser getParser(Analyzer analyzer) {
      return new NewSolrSynonymParser(true, true, analyzer) {
        @Override
        public void add(CharsRef input, CharsRef output, boolean includeOrig) {
          super.add(lowercase(input), replaceNulls(output), countWords(input) > 1 ? true : inclOrig);
        }
        private CharsRef lowercase(CharsRef chars) {
          chars = CharsRef.deepCopyOf(chars);
          final int limit = chars.offset + chars.length;
          for (int i=chars.offset;i<limit;i++) {
            chars.chars[i] = Character.toLowerCase(chars.chars[i]); // maybe not be always correct (?)
          }
          return chars;
        }
      };
      
    }
  }
  
  
  /*
   * This is a custom configuration for multi-token query-time synonym expansion.
   * 
   * Multi-tokens are searched lowercase and original parts are returned
   * 
   * Single tokens are searched as they are written in the synonym file
   * 
   * The parser also returns source tokens for the multi-token group, for 
   * single-token the behaviour is governed by settings of includeOrig
   * 
   */
  public static class BestEffortIgnoreCaseSelectively extends SynonymBuilderFactory {
  	boolean inclOrig = false;
    public void inform(ResourceLoader loader) throws IOException {
      args.put("ignoreCase", "false");
      inclOrig = args.containsKey("inclOrig") ? ((String) args.get("inclOrig")).equals("true") : false;
    }
    protected SynonymParser getParser(Analyzer analyzer) {
      return new NewSolrSynonymParser(true, true, analyzer) {
        @Override
        public void add(CharsRef input, CharsRef output, boolean includeOrig) { //is always false :(
          int count = countWords(input);
          super.add(count > 1 ? lowercase(input) : input, replaceNulls(output), count > 1 ? true : inclOrig);
        }
        private CharsRef lowercase(CharsRef chars) {
          chars = CharsRef.deepCopyOf(chars);
          final int limit = chars.offset + chars.length;
          for (int i=chars.offset;i<limit;i++) {
            chars.chars[i] = Character.toLowerCase(chars.chars[i]); // maybe not be always correct (?)
          }
          return chars;
        }
      };
      
    }
  }
  
  
  
  public static int countWords(CharsRef chars) {
    int wordCount = 1;
    int upto = chars.offset;
    final int limit = chars.offset + chars.length;
    while(upto < limit) {
      if (chars.chars[upto++] == SynonymMap.WORD_SEPARATOR) {
        wordCount++;
      }
    }
    return wordCount;
  }
  
  public static CharsRef replaceNulls(CharsRef charsRef) {
    CharsRef sanChar = CharsRef.deepCopyOf(charsRef);
    final int end = sanChar.offset + sanChar.length;
    for(int idx=sanChar.offset+1;idx<end;idx++) {
      if (sanChar.chars[idx] == SynonymMap.WORD_SEPARATOR) {
        sanChar.chars[idx] = ' ';
      }
    }
    return sanChar;
  }
}
