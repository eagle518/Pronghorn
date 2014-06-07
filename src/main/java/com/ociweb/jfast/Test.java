package com.ociweb.jfast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Properties;

import com.ociweb.jfast.error.FASTException;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.generator.DispatchLoader;
import com.ociweb.jfast.generator.FASTClassLoader;
import com.ociweb.jfast.loader.ClientConfig;
import com.ociweb.jfast.loader.TemplateCatalogConfig;
import com.ociweb.jfast.loader.TemplateLoader;
import com.ociweb.jfast.primitive.PrimitiveReader;
import com.ociweb.jfast.primitive.adapter.FASTInputByteArray;
import com.ociweb.jfast.primitive.adapter.FASTInputByteBuffer;
import com.ociweb.jfast.primitive.adapter.FASTInputStream;
import com.ociweb.jfast.stream.FASTDecoder;
import com.ociweb.jfast.stream.FASTInputReactor;
import com.ociweb.jfast.stream.FASTRingBuffer;
import com.ociweb.jfast.stream.FASTRingBufferReader;

public class Test {

    public static void main(String[] args) {
        new Test().testDecodeComplex30000();
    }
    
    public void testDecodeComplex30000() {
        
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
          
          byte[] catBytes = buildRawCatalogData();
          TemplateCatalogConfig catalog = new TemplateCatalogConfig(catBytes); 

          // connect to file

          int maxPMapCountInBytes = 2 + ((Math.max(
                  catalog.maxTemplatePMapSize(), catalog.maxNonTemplatePMapSize()) + 2) * catalog.getMaxGroupDepth());             
          
          
          FASTClassLoader.deleteFiles();
          
          FASTDecoder readerDispatch = DispatchLoader.loadDispatchReader(catBytes);
          System.err.println("using:"+readerDispatch.getClass().getSimpleName());
          FASTRingBuffer queue = readerDispatch.ringBuffer(0);

          int warmup = 128;
          int count = 1024000;
          int result = 0;
          int[] fullScript = catalog.getScriptTokens();
          
          
          byte[] preamble = new byte[catalog.clientConfig().getPreableBytes()];

          int msgs = 0;
          int grps = 0;
          long queuedBytes = 0;
          int iter = warmup;
          while (--iter >= 0) {
              FASTInputStream fastInputStream = new FASTInputStream(testDataInputStream("/performance/complex30000.dat"));
              PrimitiveReader reader = new PrimitiveReader(1024,fastInputStream, maxPMapCountInBytes);
              PrimitiveReader.setTimeout(Long.MAX_VALUE, reader);
              msgs = 0;
              grps = 0;
              int flag = 0; // same id needed for writer construction
              while (0 != (flag = FASTInputReactor.select(readerDispatch, reader, queue))) {
                  // New flags
                  // 0000 eof
                  // 0001 has sequence group to read (may be combined with end of
                  // message)
                  // 0010 has message to read
                  // neg unable to write to ring buffer

                  // consumer code can stop only at end of message if desired or
                  // for
                  // lower latency can stop at the end of every sequence entry.
                  // the call to hasMore is non-blocking with respect to the
                  // consumer and
                  // will return negative value if the ring buffer is full but it
                  // will
                  // spin lock if input stream is not ready.
                  //

                  if (0 != (flag & TemplateCatalogConfig.END_OF_MESSAGE)) {
                      msgs++;

                      // this is a template message.
                      int bufferIdx = 0;
                      if (preamble.length > 0) {
                          int i = 0;
                          int s = preamble.length;
                          while (i < s) {
                              FASTRingBufferReader.readInt(queue, bufferIdx);
                              i += 4;
                              bufferIdx++;
                          }
                      }

                      int templateId = FASTRingBufferReader.readInt(queue, bufferIdx);
                      bufferIdx += 1;// point to first field
                      assert(1 == templateId || 2 == templateId || 99 == templateId);

                      int i = catalog.getTemplateStartIdx()[templateId];
                      int limit = catalog.getTemplateLimitIdx()[templateId];
                      // System.err.println("new templateId "+templateId);
                      while (i < limit) {
                          int token = fullScript[i++];
                          // System.err.println("xxx:"+bufferIdx+" "+TokenBuilder.tokenToString(token));

                          if (isText(token)) {
                              queuedBytes += (4 * FASTRingBufferReader.readTextLength(queue, bufferIdx));
                          }

                          // find the next index after this token.
                          bufferIdx += stepSizeInRingBuffer(token);

                      }
                      queuedBytes += bufferIdx;// ring buffer bytes, NOT full
                                               // string byteVector data.

                      // must dump values in buffer or we will hang when reading.
                      // only dump at end of template not end of sequence.
                      // the removePosition must remain at the beginning until
                      // message is complete.
                      FASTRingBuffer.dump(queue);
                  }
                  grps++;

              }
              //fastInput.reset();
              PrimitiveReader.reset(reader);
              readerDispatch.reset();
              readerDispatch.reset(catalog.dictionaryFactory());
          }

          iter = count;
          while (--iter >= 0) {
              FASTInputStream fastInputStream = new FASTInputStream(testDataInputStream("/performance/complex30000.dat"));
              PrimitiveReader reader = new PrimitiveReader(1024*1024*2,fastInputStream, maxPMapCountInBytes);
              PrimitiveReader.fetch(reader);//Pre-load the file so we only count the parse time.
              
              System.gc();
              
              PrimitiveReader.setTimeout(Long.MAX_VALUE, reader);
              
              double start = System.nanoTime();

              int flag;
              while (0 != (flag = FASTInputReactor.select(readerDispatch, reader, queue))) {
                  if (0 != (flag & TemplateCatalogConfig.END_OF_MESSAGE)) {
                      result |= FASTRingBufferReader.readInt(queue, 0);// must do some real work or
                                                     // hot-spot may delete this
                                                     // loop.
                                      
                      
                  } else if (flag < 0) {
                      
                      // must dump values in buffer or we will hang when reading.
                      FASTRingBufferReader.dump(queue);
                  }
              }

              double duration = System.nanoTime() - start;
              long totalTestBytes = fastInputStream.totalBytes();
              if ((0x7F & iter) == 0) {
                  int ns = (int) duration;
                  float mmsgPerSec = (msgs * (float) 1000l / ns);
                  float nsPerByte = (ns / (float) totalTestBytes);
                  int mbps = (int) ((1000l * totalTestBytes * 8l) / ns);

                  System.err.println("Duration:" + ns + "ns " + " " + mmsgPerSec + "MM/s " + " " + nsPerByte + "nspB "
                          + " " + mbps + "mbps " + " In:" + totalTestBytes + " Out:" + queuedBytes + " pct "
                          + (totalTestBytes / (float) queuedBytes) + " Messages:" + msgs); // Phrases/Clauses
                  // Helps let us kill off the job.
              }

              // //////
              // reset the data to run the test again.
              // //////
              //fastInput.reset();
              PrimitiveReader.reset(reader);
              readerDispatch.reset();
              readerDispatch.reset(catalog.dictionaryFactory());

          }
          assert(result != 0);

      }
    private boolean isText(int token) {
        return 0x08 == (0x1F & (token >>> TokenBuilder.SHIFT_TYPE));
    }

    private int stepSizeInRingBuffer(int token) {
        int stepSize = 0;
        if (0 == (token & (16 << TokenBuilder.SHIFT_TYPE))) {
            // 0????
            if (0 == (token & (8 << TokenBuilder.SHIFT_TYPE))) {
                // 00???
                if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                    // int
                    stepSize = 1;
                } else {
                    // long
                    stepSize = 2;
                }
            } else {
                // 01???
                if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                    // int for text (takes up 2 slots)
                    stepSize = 2;
                } else {
                    // 011??
                    if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                        // 0110? Decimal and DecimalOptional
                        stepSize = 3;
                    } else {
                        // int for bytes
                        stepSize = 0;// BYTES ARE NOT IMPLEMENTED YET BUT WILL
                                     // BE 2;
                    }
                }
            }
        } else {
            if (0 == (token & (8 << TokenBuilder.SHIFT_TYPE))) {
                // 10???
                if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                    // 100??
                    // Group Type, no others defined so no need to keep checking
                    stepSize = 0;
                } else {
                    // 101??
                    // Length Type, no others defined so no need to keep
                    // checking
                    // Only happens once before a node sequence so push it on
                    // the count stack
                    stepSize = 1;
                }
            } else {
                // 11???
                // Dictionary Type, no others defined so no need to keep
                // checking
                stepSize = 0;
            }
        }

        return stepSize;
    }
    
    static FASTInputByteArray buildInputForTestingByteArray(File fileSource) {
        byte[] fileData = null;
        try {
            // do not want to time file access so copy file to memory
            fileData = new byte[(int) fileSource.length()];
            FileInputStream inputStream = new FileInputStream(fileSource);
            int readBytes = inputStream.read(fileData);
            inputStream.close();
            assert(fileData.length==readBytes);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        FASTInputByteArray fastInput = new FASTInputByteArray(fileData);
        return fastInput;
    }
    
    static InputStream testDataInputStream(String resource) {
        
        InputStream resourceInput = Test.class.getResourceAsStream(resource);
        if (null!=resourceInput) {
            return resourceInput;            
        }
        
        try {
            return new FileInputStream(new File(resource));
        } catch (FileNotFoundException e) {
            throw new FASTException(e);
        }
    }

    public static byte[] buildRawCatalogData() {
        //this example uses the preamble feature
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setPreableBytes((short)4);

        ByteArrayOutputStream catalogBuffer = new ByteArrayOutputStream(4096);
        try {
            TemplateLoader.buildCatalog(catalogBuffer, "/performance/example.xml", clientConfig);
        } catch (Exception e) {
            e.printStackTrace();
        }

        assert(catalogBuffer.size() > 0);

        byte[] catalogByteArray = catalogBuffer.toByteArray();
        return catalogByteArray;
    }

}