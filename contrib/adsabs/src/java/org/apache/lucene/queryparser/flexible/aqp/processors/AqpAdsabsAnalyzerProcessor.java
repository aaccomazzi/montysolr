package org.apache.lucene.queryparser.flexible.aqp.processors;


import org.apache.lucene.queryparser.flexible.aqp.config.AqpAdsabsQueryConfigHandler;
import org.apache.lucene.queryparser.flexible.aqp.config.AqpRequestParams;
import org.apache.lucene.queryparser.flexible.aqp.nodes.AqpAdsabsSynonymQueryNode;
import org.apache.lucene.queryparser.flexible.aqp.nodes.AqpAnalyzedQueryNode;
import org.apache.lucene.queryparser.flexible.aqp.nodes.AqpFunctionQueryNode;
import org.apache.lucene.queryparser.flexible.aqp.nodes.AqpNonAnalyzedQueryNode;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.core.config.QueryConfigHandler;
import org.apache.lucene.queryparser.flexible.core.nodes.FieldQueryNode;
import org.apache.lucene.queryparser.flexible.core.nodes.QueryNode;
import org.apache.solr.common.params.SolrParams;

/**
 * This processor prevents analysis to happen for nodes that are 
 * inside certain node types. This is needed especially for the 
 * functional queries where we do not know how the values are to 
 * be processed and the function itself should decide.
 * 
 * @author rchyla
 *
 */
public class AqpAdsabsAnalyzerProcessor extends AqpAnalyzerQueryNodeProcessor {

	public final static String ORIGINAL_VALUE = "ORIGINAL_ANALYZED_VALUE";
  public final static String ANALYZED = "already_analyzed";
  
  private boolean enteredCleanZone = false;
  private int counter = 0;
  

  @Override
  protected QueryNode preProcessNode(QueryNode node) throws QueryNodeException {
    if (enteredCleanZone == true) {
      counter++;
    }
    else if (node instanceof AqpNonAnalyzedQueryNode || node.getTag(ANALYZED) != null) {
      enteredCleanZone = true;
      counter++;
    }
    else if (node instanceof AqpFunctionQueryNode && ((AqpFunctionQueryNode) node).canBeAnalyzed() == false) {
      enteredCleanZone = true;
      counter++;
    }

    return super.preProcessNode(node);
  }

  @Override
  protected QueryNode postProcessNode(QueryNode node)
  throws QueryNodeException {
    if (enteredCleanZone == true) {
      counter--;
      if (counter == 0) {
        enteredCleanZone = false;
      }
      return node;
    }

    String fv = null;
    if (node instanceof FieldQueryNode) {
      fv = ((FieldQueryNode) node).getTextAsString();
    }
    QueryNode rn = super.postProcessNode(node);
    if (rn != node || node instanceof FieldQueryNode 
        //&& !((FieldQueryNode)node).getTextAsString().equals(fv)
        ) {
      rn.setTag(ORIGINAL_VALUE, fv);
      rn.setTag(ANALYZED, true);
      //return new AqpAnalyzedQueryNode(rn);
    }
    
    return rn;

  }
  
}
