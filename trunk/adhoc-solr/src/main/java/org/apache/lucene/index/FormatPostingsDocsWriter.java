package org.apache.lucene.index;

/**
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

/** Consumes doc & freq, writing them using the current
 *  index file format */

import java.io.Closeable;
import java.io.IOException;

import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.UnicodeUtil;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.store.IndexOutput;

final class FormatPostingsDocsWriter extends FormatPostingsDocsConsumer implements Closeable {

  final IndexOutput out;
  final FormatPostingsTermsWriter parent;
  final FormatPostingsPositionsWriter posWriter;
  final DefaultSkipListWriter skipListWriter;
  final int skipInterval;
  final int totalNumDocs;

  boolean omitTermFreqAndPositions;
  boolean storePayloads;
  long freqStart;
  FieldInfo fieldInfo;
  
  
  
  private boolean currentIsUseCompress=true;

//  public boolean isCurrentIsUseCompress() {
//    return currentIsUseCompress;
//}


FormatPostingsDocsWriter(SegmentWriteState state, FormatPostingsTermsWriter parent) throws IOException {
    this.parent = parent;
    out = parent.parent.dir.createOutput(IndexFileNames.segmentFileName(parent.parent.segment, IndexFileNames.FREQ_EXTENSION));
    this.currentIsUseCompress=true;//IndexWriterConfig.IsCompressFrq();
    out.writeVInt(this.currentIsUseCompress?1:0);
    boolean success = false;
    try {
      totalNumDocs = parent.parent.totalNumDocs;
      skipInterval = parent.parent.termsOut.skipInterval;
      skipListWriter = parent.parent.skipListWriter;
      skipListWriter.setFreqOutput(out);
      
      posWriter = new FormatPostingsPositionsWriter(state, this);
      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(out);
      }
    }
  }
  

  void setField(FieldInfo fieldInfo) {
    this.fieldInfo = fieldInfo;
    omitTermFreqAndPositions = fieldInfo.indexOptions == IndexOptions.DOCS_ONLY;
    storePayloads = fieldInfo.storePayloads;
    posWriter.setField(fieldInfo);
  }

  int lastDocID;
  int df;


  
  @Override
  FormatPostingsPositionsConsumer addDoc(int docID, int termDocFreq) throws IOException {

      final int delta = docID - lastDocID;
    assert docID < totalNumDocs: "docID=" + docID + " totalNumDocs=" + totalNumDocs;
    if (docID < 0 || (df > 0 && delta <= 0))
        throw new CorruptIndexException("docs out of order (" + docID + " <= " + lastDocID + " ) (out: " + out + ")");
    
      if ((++df % skipInterval) == 0) {
        // TODO: abstraction violation
        skipListWriter.setSkipData(lastDocID, storePayloads, posWriter.lastPayloadLength);
        skipListWriter.bufferSkip(df);
      }
    lastDocID = docID;
    if (omitTermFreqAndPositions)
    	this.out.writeCompressblock(delta);
    else if (1 == termDocFreq)
    	this.out.writeCompressblock((delta<<1) | 1);
    else {
    	this.out.writeCompressblock(delta<<1);
    	this.out.writeCompressblock(termDocFreq);
    }

    return posWriter;
  }
  


  private final TermInfo termInfo = new TermInfo();  // minimize consing
  final UnicodeUtil.UTF8Result utf8 = new UnicodeUtil.UTF8Result();

  /** Called when we are done adding docs to this term */
  @Override
  void finish() throws IOException {
	  this.out.flushCompressBlock();
    long skipPointer = skipListWriter.writeSkip(out);
    termInfo.set(df, parent.freqStart, parent.proxStart, (int) (skipPointer - parent.freqStart));

    // TODO: we could do this incrementally
    UnicodeUtil.UTF16toUTF8(parent.currentTerm, parent.currentTermStart, utf8);

    if (df > 0) {
      parent.termsOut.add(parent.currentTermobj,fieldInfo.number,
                          utf8.result,
                          utf8.length,
                          termInfo);
    }

    lastDocID = 0;
    df = 0;
  }

  public void close() throws IOException {
    IOUtils.close(out, posWriter);
  }


@Override
public boolean reset() {
	if(this.currentIsUseCompress)
	{
		this.out.setUsedBlock();
	}
	this.out.resetBlockMode();
	return true;
}


}
