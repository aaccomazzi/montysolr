package org.apache.lucene.queryparser.flexible.aqp.processors;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CachingTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.NumericTokenStream.NumericTermAttribute;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.queryparser.flexible.aqp.AqpDEFOPMarkPlainNodes;
import org.apache.lucene.queryparser.flexible.aqp.config.AqpAdsabsQueryConfigHandler;
import org.apache.lucene.queryparser.flexible.aqp.config.AqpStandardQueryConfigHandler;
import org.apache.lucene.queryparser.flexible.aqp.nodes.AqpDefopQueryNode;
import org.apache.lucene.queryparser.flexible.aqp.nodes.AqpNonAnalyzedQueryNode;
import org.apache.lucene.queryparser.flexible.aqp.nodes.AqpOrQueryNode;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.core.config.QueryConfigHandler;
import org.apache.lucene.queryparser.flexible.core.messages.QueryParserMessages;
import org.apache.lucene.queryparser.flexible.core.nodes.BooleanQueryNode;
import org.apache.lucene.queryparser.flexible.core.nodes.FieldQueryNode;
import org.apache.lucene.queryparser.flexible.core.nodes.FieldableNode;
import org.apache.lucene.queryparser.flexible.core.nodes.GroupQueryNode;
import org.apache.lucene.queryparser.flexible.core.nodes.ModifierQueryNode;
import org.apache.lucene.queryparser.flexible.core.nodes.QueryNode;
import org.apache.lucene.queryparser.flexible.core.nodes.TextableQueryNode;
import org.apache.lucene.queryparser.flexible.core.processors.QueryNodeProcessor;
import org.apache.lucene.queryparser.flexible.core.processors.QueryNodeProcessorImpl;
import org.apache.lucene.queryparser.flexible.core.util.UnescapedCharSequence;
import org.apache.lucene.queryparser.flexible.messages.MessageImpl;
import org.apache.lucene.queryparser.flexible.standard.config.StandardQueryConfigHandler;
import org.apache.lucene.queryparser.flexible.standard.config.StandardQueryConfigHandler.ConfigurationKeys;

public class AqpMultiWordProcessor extends QueryNodeProcessorImpl {

	private CachingTokenFilter buffer;
	private CharTermAttribute termAtt;
	private NumericTermAttribute numAtt;
	private TypeAttribute typeAtt;
  private PositionIncrementAttribute posAtt;
  private OffsetAttribute offsetAtt;
	
	public AqpMultiWordProcessor() {
		// empty
	}

	@Override
	public QueryNode process(QueryNode queryTree) throws QueryNodeException {
		QueryConfigHandler config = this.getQueryConfigHandler();

		if (config.has(AqpAdsabsQueryConfigHandler.ConfigurationKeys.SOLR_REQUEST)
				&& config.get(AqpAdsabsQueryConfigHandler.ConfigurationKeys.SOLR_REQUEST)
				.getRequest() != null) {
			return super.process(queryTree);
		}

		return queryTree;
	}

	@Override
	protected QueryNode postProcessNode(QueryNode node)
	throws QueryNodeException {
		
		if (node instanceof AqpDefopQueryNode) {
			LinkedList<QueryNode> newChildren = new LinkedList<QueryNode>();
			List<QueryNode> children = node.getChildren();
			
			String multiToken;
			
			for (int i=0;i<children.size();i++) {
			  QueryNode child = children.get(i);
				QueryNode terminalNode = getTerminalNode(child);
				
				multiToken = (String) terminalNode.getTag(AqpDEFOPMarkPlainNodes.PLAIN_TOKEN_CONCATENATED);
				if (multiToken != null && analyzeMultiToken(((FieldableNode) terminalNode).getField(), multiToken) > 0) {
					  i = expandWithSynonyms(children, newChildren, terminalNode, i, ((ModifierQueryNode) child).getModifier());
				}
				else {
					newChildren.add(child);
				}
			}
			node.set(newChildren);
		}
		return node;
		
	}


	private int expandWithSynonyms(List<QueryNode> children,
      LinkedList<QueryNode> newChildren, QueryNode terminalNode, 
      Integer i, ModifierQueryNode.Modifier modifier) {
    
	  FieldableNode fieldNode = (FieldableNode) terminalNode;
	  int startingPosition = ((FieldQueryNode) fieldNode).getBegin();
	  int maxOffset = ((String) terminalNode.getTag(AqpDEFOPMarkPlainNodes.PLAIN_TOKEN_CONCATENATED)).length() + startingPosition;
	  
    buffer.reset();
    Integer startOffset = 0;
    Integer endOffset = 0;
    
    LinkedList<QueryNode> synChildren = new LinkedList<QueryNode>();
    
    // The difficulty here is that we are looking into two streams of tokens
    // the buffer shows us synonyms, but we must find their source-tokens
    // inside chilren[]
    try {
      while (buffer.incrementToken()) {
          
        typeAtt = buffer.getAttribute(TypeAttribute.class);
        offsetAtt = buffer.getAttribute(OffsetAttribute.class);
        
        // seek until we find the first synonym
        if (!typeAtt.type().equals(SynonymFilter.TYPE_SYNONYM)) {
          if (synChildren.size()>0) {
            // we already have synonyms from the previous run            
            newChildren.add(new ModifierQueryNode(new AqpOrQueryNode(synChildren), 
                          modifier));
            synChildren = new LinkedList<QueryNode>();
            
            // find tokens that are *after* the syn-tokens (but not exceeding offset of the current buffer)
            for (int j=i;j<children.size();j++) {
              FieldQueryNode tn = (FieldQueryNode) getTerminalNode(children.get(j));
              if (tn.getBegin() >= startOffset+startingPosition && tn.getEnd() <= endOffset+startingPosition) { // token which is inside the synonym
                i++;
              }
              else if(tn.getEnd() <= maxOffset) {
                newChildren.add(children.get(j));
                i++;
              }
              else {
                break;
              }
              
            }
          }
          startOffset = endOffset = 0;
          continue;
        }
        
        // discover offsets (the longest range) for synonyms
        startOffset = offsetAtt.startOffset();
        endOffset = offsetAtt.endOffset() > endOffset ? offsetAtt.endOffset() : endOffset;
        
        // find tokens that are *before* the syn-tokens
        for (int j=i;j<children.size();j++) {
          FieldQueryNode tn = (FieldQueryNode) getTerminalNode(children.get(j));
          if (tn.getBegin() >= startOffset+startingPosition && tn.getEnd() <= endOffset+startingPosition) { // token which is inside the synonym
            fieldNode = (FieldableNode) tn; //mark the current synonym source (its first token)
            break;
          }
          newChildren.add(children.get(j));
          i++;
        }
        
        // add synonym (it is already analyzed, so we wrap it into AqpNonAnalyzedQN)
        FieldableNode newNode = (FieldableNode) fieldNode.cloneTree();
        if (buffer.hasAttribute(CharTermAttribute.class)) {
          termAtt = buffer.getAttribute(CharTermAttribute.class);
          ((TextableQueryNode) newNode).setText(termAtt.toString());
        }
        else {
          numAtt = buffer.getAttribute(NumericTermAttribute.class);
          ((TextableQueryNode) newNode).setText(new Long(numAtt.getRawValue()).toString());
        }
        synChildren.add(new AqpNonAnalyzedQueryNode((FieldQueryNode) newNode));
      }
    } catch (IOException e) {
      getQueryConfigHandler().get(AqpAdsabsQueryConfigHandler.ConfigurationKeys.SOLR_LOGGER).error(e.getLocalizedMessage());
    } catch (CloneNotSupportedException e) {
      getQueryConfigHandler().get(AqpAdsabsQueryConfigHandler.ConfigurationKeys.SOLR_LOGGER).error(e.getLocalizedMessage());
    }
    
    if (synChildren.size()>0) {
      newChildren.add(new GroupQueryNode(new AqpOrQueryNode(synChildren)));
      synChildren = new LinkedList<QueryNode>();
    }
    
    return i;
  }

  private QueryNode getTerminalNode(QueryNode node) {
		if (node.isLeaf()) {
			return node;
		}
		for (QueryNode child: node.getChildren()) {
			if (child.containsTag(AqpDEFOPMarkPlainNodes.PLAIN_TOKEN)) {
				return child;
			}
			return getTerminalNode(child);
		}
		return null;
	}
	
	private int analyzeMultiToken(CharSequence field, String multiToken) {
			

		QueryConfigHandler config = this.getQueryConfigHandler();

		Locale locale = getQueryConfigHandler().get(ConfigurationKeys.LOCALE);
		if (locale == null) {
			locale = Locale.getDefault();
		}
		Analyzer analyzer = config.get(StandardQueryConfigHandler.ConfigurationKeys.ANALYZER);

		TokenStream source = null;
		try {
			source = analyzer.tokenStream(field.toString(),
					new StringReader(multiToken));
			source.reset();
		} catch (IOException e1) {
			return -1;
		}

		buffer = new CachingTokenFilter(source);
		int numSynonyms = 0;

		try {
			while (buffer.incrementToken()) {
				typeAtt = buffer.getAttribute(TypeAttribute.class);
				if (typeAtt.type().equals(SynonymFilter.TYPE_SYNONYM)) {
					numSynonyms++;
				}
			}
		} catch (IOException e) {
			// pass
		}

		try {
			// rewind the buffer stream
			buffer.reset();

			// close original stream - all tokens buffered
			source.close();
		} catch (IOException e) {
			// ignore
		}
		
		return numSynonyms;
	}

	@Override
	protected QueryNode preProcessNode(QueryNode node)
	throws QueryNodeException {
		return node;
	}

	@Override
	protected List<QueryNode> setChildrenOrder(List<QueryNode> children)
	throws QueryNodeException {
		return children;
	}

	protected QueryNode expandMultiToken(QueryNode node) throws QueryNodeException {
	  
	  
    
		FieldableNode fieldNode = (FieldableNode) node;


		LinkedList<QueryNode> children = new LinkedList<QueryNode>();
		//children.add(new AqpNonAnalyzedQueryNode((FieldQueryNode) fieldNode)); // original input

		buffer.reset();
		Integer previousPos = null;

		try {
			while (buffer.incrementToken()) {
				
			  posAtt = buffer.getAttribute(PositionIncrementAttribute.class);
				typeAtt = buffer.getAttribute(TypeAttribute.class);
				if (!typeAtt.type().equals(SynonymFilter.TYPE_SYNONYM)) {
					continue;
				}
				
				FieldableNode newNode = (FieldableNode) fieldNode.cloneTree();
				
				if (buffer.hasAttribute(CharTermAttribute.class)) {
					termAtt = buffer.getAttribute(CharTermAttribute.class);
					((TextableQueryNode) newNode).setText(termAtt.toString());
				}
				else {
					numAtt = buffer.getAttribute(NumericTermAttribute.class);
					((TextableQueryNode) newNode).setText(new Long(numAtt.getRawValue()).toString());
				}

				children.add(new AqpNonAnalyzedQueryNode((FieldQueryNode) newNode));
			}
		} catch (IOException e) {
			getQueryConfigHandler().get(AqpAdsabsQueryConfigHandler.ConfigurationKeys.SOLR_LOGGER).error(e.getLocalizedMessage());
		} catch (CloneNotSupportedException e) {
			getQueryConfigHandler().get(AqpAdsabsQueryConfigHandler.ConfigurationKeys.SOLR_LOGGER).error(e.getLocalizedMessage());
		}

		if (children.size() < 1) {
			throw new QueryNodeException(new MessageImpl(
					QueryParserMessages.PARAMETER_VALUE_NOT_SUPPORTED,
					"This should never hapeeeeennnn! Error expanding synonyms for: "
					+ node.toString() + ""));
		}

		return new GroupQueryNode(new AqpOrQueryNode(children));

	}
}