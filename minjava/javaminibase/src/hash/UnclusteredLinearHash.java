package hash;

import diskmgr.Page;
import global.*;
import heap.*;
import iterator.TupleUtils;

public class UnclusteredLinearHash {

    public int hash1 = 4;
    private int hash2 = 8;
    private int current_tuples=0;
    private int tuple_threshold=0;
    private int lower_threshold = 0;
    private String prefix= "unclst_buc_";
    public int splitPointer = 0;
    public int number_of_tuples_in_a_page = 0;
    private int totalTuples = 0;
    public int numBuckets=hash1;
    
    private AttrType[] _in;
    public int indexField;
    
    private String fileName;

    private short numberOfCols;
    private short[] strSizes;
    
    private Heapfile relation;
    
    
    private AttrType keyPageAttr[];
    
    private AttrType dir_f_Attr[];
    private short[] dir_f_str_lens;
    
    private AttrType keyFileAttr[];
    private short[] keyFileStrLens;
    
    public String directoryFile;
    private Heapfile directory;


    public UnclusteredLinearHash(int utilization, String filename, int attr_num, short[] strSizes, AttrType[] in, String indexfilename) throws Exception {
        
    	this.fileName = filename;
        this.indexField = attr_num;
        this.directoryFile = indexfilename;
        directory = new Heapfile(this.directoryFile);
        
        this._in = in.clone();
        this.strSizes = strSizes.clone();
        this.numberOfCols = (short)_in.length;
        
        relation = new Heapfile(fileName);
        
        prefix += fileName+"_"+attr_num+"_";
        
        dir_f_Attr = new AttrType[1];
        dir_f_Attr[0] = new AttrType(AttrType.attrString);
        dir_f_str_lens = new short[1];
        dir_f_str_lens[0] = GlobalConst.MAX_NAME+2;
        
        keyFileAttr = new AttrType[2];
        keyFileAttr[0] = _in[attr_num-1];
        keyFileAttr[1] = new AttrType(AttrType.attrInteger);
        
        if(_in[attr_num-1].attrType==AttrType.attrString) {
        	keyFileStrLens = new short[1];
        	keyFileStrLens[0] = strSizes[0];
        }        
        keyPageAttr = new AttrType[2];
        keyPageAttr[0] = new AttrType(AttrType.attrInteger);
        keyPageAttr[1] = new AttrType(AttrType.attrInteger);
        
        
        Tuple t = new Tuple();
        t.setHdr((short)2, keyPageAttr, null);
        int size = t.size();

        number_of_tuples_in_a_page = (GlobalConst.MAX_SPACE - HFPage.DPFIXED) / (size + HFPage.SIZE_OF_SLOT);
        
        int tmp = (int)((relation.getRecCnt()*100.0)/(GlobalConst.MAX_PAGE_UTILIZATION*number_of_tuples_in_a_page));
        hash1 = 2;
        while(hash1<=tmp)hash1*=2;
        hash2 = 2*hash1;
        numBuckets = hash1;
        setTotalTuplesAndThreshold();
        createIndex();
    }
    
    public UnclusteredLinearHash(String indexFile, int hash1, int numBuckets, 
    		int splitPointer, int attr_no, String relationName,
    		AttrType[] in, short[] strLens) throws Exception{
    	this.hash1 = hash1;
    	this.hash2 = 2*hash1;
    	this.numBuckets = numBuckets-1;
    	this.splitPointer = splitPointer;        
    	this.indexField = attr_no;
        this.directoryFile = indexFile;
        directory = new Heapfile(this.directoryFile);
        this._in = in.clone();
        this.strSizes = strLens.clone();
        this.numberOfCols = (short)_in.length;
        fileName = relationName;
        relation = new Heapfile(fileName);
        current_tuples = relation.getRecCnt();
        
        prefix += fileName+"_"+indexField+"_";
        
        dir_f_Attr = new AttrType[1];
        dir_f_Attr[0] = new AttrType(AttrType.attrString);
        dir_f_str_lens = new short[1];
        dir_f_str_lens[0] = GlobalConst.MAX_NAME+2;
        
        keyFileAttr = new AttrType[2];
        keyFileAttr[0] = _in[indexField-1];
        keyFileAttr[1] = new AttrType(AttrType.attrInteger);
        
        if(_in[indexField-1].attrType==AttrType.attrString) {
        	keyFileStrLens = new short[1];
        	keyFileStrLens[0] = strSizes[0];
        }
        
        keyPageAttr = new AttrType[2];
        keyPageAttr[0] = new AttrType(AttrType.attrInteger);
        keyPageAttr[1] = new AttrType(AttrType.attrInteger);
        
        
        Tuple t = new Tuple();
        t.setHdr((short)2, keyPageAttr, null);
        int size = t.size();

        number_of_tuples_in_a_page = (GlobalConst.MAX_SPACE - HFPage.DPFIXED) / (size + HFPage.SIZE_OF_SLOT);
        setTotalTuplesAndThreshold();
        
    }
   
    
    private void createIndex() throws Exception{
    	Scan scan = new Scan(relation);
    	Tuple t = null;
    	RID rid = new RID();
    	while((t=scan.getNext(rid))!=null) {
    		insertInIndex(t,rid);
    	}
    }
    
    public void deleteFromIndex(Tuple t, RID rid) throws Exception{
    	t.setHdr(numberOfCols, _in, strSizes);
		t = new Tuple(t);
		t.setHdr(numberOfCols, _in, strSizes);
		int hash = calculateHashValueForTuple(t,false);
		if(hash<splitPointer) {
			hash = calculateHashValueForTuple(t, true);
		}
		Heapfile hf = new Heapfile(prefix+hash);
		Scan scan = new Scan(hf);
		Tuple t1 = null;
		RID bucket_tuple_rid = new RID();
		while((t1=scan.getNext(bucket_tuple_rid))!=null) {
			t1.setHdr((short)keyFileAttr.length, keyFileAttr, keyFileStrLens);
			if(TupleUtils.CompareTupleWithTuple(_in[indexField-1], t1, 1,t,indexField)==0) {
				scan.closescan();
				PageId curPagePID = new PageId(t1.getIntFld(2));
				FIRST:for(;curPagePID.pid!=GlobalConst.INVALID_PAGE;) {
					Page page = new Page();
					pinPage(curPagePID, page, false);
					HFPage curPage = new HFPage(page);
					for(RID curRecord = curPage.firstRecord();
							curRecord!=null;
							curRecord=curPage.nextRecord(curRecord)) {
						Tuple t2 = curPage.getRecord(curRecord);
						if(rid.equals(ridFromTuple(t2))){
							curPage.deleteRecord(curRecord);
							current_tuples--;
							if(curPage.empty()) {
								PageId prev = curPage.getPrevPage();
								PageId next = curPage.getNextPage();
								unpinPage(curPagePID, true);
								freePage(curPagePID);
								if(prev.pid != GlobalConst.INVALID_PAGE) {
									pinPage(prev, page, false);
									curPage = new HFPage(page);
									curPage.setNextPage(next);
									unpinPage(prev, true);
									if(next.pid!=GlobalConst.INVALID_PAGE) {
										pinPage(next, page, false);
										curPage = new HFPage(page);
										curPage.setPrevPage(prev);
										unpinPage(next, true);
									}
								}else {
									if(next.pid!=GlobalConst.INVALID_PAGE) {
										pinPage(next, page, false);
										curPage = new HFPage(page);
										curPage.setPrevPage(prev);
										unpinPage(next, true);
										t1.setIntFld(2, next.pid);
										hf.updateRecord(bucket_tuple_rid, t1);
									}else {
										hf.deleteRecord(bucket_tuple_rid);
										if(hf.isEmpty()) {
											deleteFromDirectory(prefix+hash);
										}
									}
								}
								break FIRST;
							}
							unpinPage(curPagePID, true);
							break FIRST;
						}
					}
					unpinPage(curPagePID, false);
					curPagePID = curPage.getNextPage();
				}
				break;
			}
		}
		if(current_tuples==lower_threshold) {
			if(splitPointer==0 && hash1==2) {
				return;
			}
			int reHashBucket = 0;
			int newBucket = 0;
			if(splitPointer==0) {
				reHashBucket = hash1-1;
				newBucket = (hash1/2)-1;
			}else {
				reHashBucket = splitPointer+hash1-1;
				newBucket = splitPointer-1;
			}
			Heapfile oldFile = new Heapfile(prefix+reHashBucket);
			if(!oldFile.isEmpty()) {
				Heapfile newFile = new Heapfile(prefix+newBucket);
				if(newFile.isEmpty())setupDirectory(prefix+newBucket);
				scan = new Scan(oldFile);
				while((t=scan.getNext(new RID()))!=null) {
					t.setHdr((short)2, keyFileAttr, keyFileStrLens);
					newFile.insertRecord(t.getTupleByteArray());
				}
				oldFile.deleteFile();
				deleteFromDirectory(prefix+reHashBucket);

			}
			decrementSplit();
		}
    }
    
    private void setupDirectory(String name) throws Exception{
    	Tuple t = new Tuple();
		t.setHdr((short)1, dir_f_Attr, dir_f_str_lens);
		t = new Tuple(t.size());
   		t.setHdr((short)1, dir_f_Attr, dir_f_str_lens);
   		t.setStrFld(1, name);
   	    directory.insertRecord(t.getTupleByteArray());
    }
    
    private void deleteFromDirectory(String name) throws Exception{
    	Scan scan = new Scan(directory);
    	Tuple t = null;
    	RID rid = new RID();
    	while((t = scan.getNext(rid))!=null) {
       		t.setHdr((short)1, dir_f_Attr, dir_f_str_lens);
       		if(t.getStrFld(1).equalsIgnoreCase(name)) {
       			scan.closescan();
       			directory.deleteRecord(rid);
       			break;
       		}
    	}
    }
    
    public void insertInIndex(Tuple t, RID rid) throws Exception{
    	t.setHdr(numberOfCols, _in, strSizes);
		t = new Tuple(t);
		t.setHdr(numberOfCols, _in, strSizes);
		int hash = calculateHashValueForTuple(t,false);
		if(hash<splitPointer) {
			hash = calculateHashValueForTuple(t, true);
		}
		if(hash>=0) {
			Heapfile hf = new Heapfile(prefix+hash);
			if(hf.isEmpty()) {
				setupDirectory(prefix+hash);
			}
			Scan bucketScan = new Scan(hf);
			Tuple t2 = null;
			PageId result = null;
			while((t2 = bucketScan.getNext(new RID()))!=null) {
				t2.setHdr((short)2, keyFileAttr, keyFileStrLens);
				if(TupleUtils.CompareTupleWithTuple(_in[indexField-1], t, indexField, t2, 1)==0) {
					result = new PageId(t2.getIntFld(2));
					bucketScan.closescan();
					break;
				}
			}
			bucketScan.closescan();
			if(result==null) {
				Page page = new Page();
				PageId newPage = newPage(page, 1);
				HFPage hfp = new HFPage();
				hfp.init(newPage, page);
				hfp.insertRecord(tupleFromRid(rid).getTupleByteArray());
				unpinPage(newPage, true);
				result = newPage;
				hf.insertRecord(keyTupleFromTuple(t, newPage.pid).getTupleByteArray());
			}else {
				Tuple ridT = tupleFromRid(rid);
				while(result!=null) {
					Page page = new Page();
					pinPage(result, page, false);
					HFPage hfp = new HFPage(page);
					if(hfp.available_space()>=ridT.getTupleByteArray().length) {
						hfp.insertRecord(ridT.getTupleByteArray());
						unpinPage(result, true);
						break;
					}
					if(hfp.getNextPage().pid==GlobalConst.INVALID_PAGE) {
						PageId newPage = newPage(page, 1);
						HFPage nhfp = new HFPage();
						nhfp.init(newPage, page);
						nhfp.insertRecord(tupleFromRid(rid).getTupleByteArray());
						nhfp.setPrevPage(result);
						unpinPage(newPage, true);
						hfp.setNextPage(newPage);
						unpinPage(result, true);
						break;						
					}
					result = hfp.getNextPage();
					unpinPage(hfp.getCurPage(),false);

				}
			}
			current_tuples++;
            if (tuple_threshold == current_tuples) {
                Heapfile f = new Heapfile(prefix + splitPointer);
                Heapfile sf = new Heapfile(null);
                bucketScan = new Scan(f);
                Tuple t1 = null;
                RID tmpRID = new RID();
                while ((t1 = bucketScan.getNext(tmpRID)) != null) {
                    t1.setHdr((short) 2, keyFileAttr, keyFileStrLens);
                    int newBucket = calculateHashValueFromHashTuple(t1, true);
                    if (newBucket != splitPointer) {
                        sf.insertRecord(tupleFromRid(tmpRID).getTupleByteArray());
                        Heapfile newBF = new Heapfile(prefix + newBucket);
                        if(newBF.isEmpty())setupDirectory(prefix+splitPointer);
                        newBF.insertRecord(t1.getTupleByteArray());
                    }
                }
                bucketScan.closescan();
                bucketScan = new Scan(sf);
                while ((t1 = bucketScan.getNext(new RID())) != null) {
                    f.deleteRecord(ridFromTuple(t1));
                }
                sf.deleteFile();
                increamentSplit();
                setTotalTuplesAndThreshold();

            }
		}
    }

    public void printIndex() throws Exception{
		Scan scan = new Scan(directory);
		Tuple t = null;
		while((t = scan.getNext(new RID()))!=null) {
			t.setHdr((short)1, dir_f_Attr, dir_f_str_lens);
			String bucketName = t.getStrFld(1);
			
			String hash[] = bucketName.split("_");
			System.out.println("\n\n====Printing the bucket with hash value::"+hash[hash.length-1]+"====\n\n");
			Scan innerScan = new Scan(new Heapfile(bucketName));
			while((t=innerScan.getNext(new RID()))!=null) {
				t.setHdr((short)2, keyFileAttr, keyFileStrLens);
				String result = "[ key = ";
				switch(keyFileAttr[0].attrType) {
				case AttrType.attrInteger:
					result+=t.getIntFld(1);
					break;
				case AttrType.attrReal:
					result+=t.getFloFld(1);
					break;
				case AttrType.attrString:
					result+=t.getStrFld(1);
					break;
				}
				result+=", RIDs = [ ";
				System.out.print(result);
				PageId header = new PageId(t.getIntFld(2));
				while(header.pid!=GlobalConst.INVALID_PAGE) {
					Page page = new Page();
					pinPage(header, page, false);
					HFPage hfp  =new HFPage(page);
					RID tmp = hfp.firstRecord();
					while(tmp!=null) {
						Tuple rid = hfp.getRecord(tmp);
						rid.setHdr((short)2, keyPageAttr, null);
						System.out.print("[Page ID:: "+rid.getIntFld(1)+" Slot No:: "+rid.getIntFld(2)+"], ");
						tmp = hfp.nextRecord(tmp);
					}
					header = hfp.getNextPage();
					unpinPage(hfp.getCurPage(), false);
				}
				System.out.println("]");
			}
			innerScan.closescan();
		}
		scan.closescan();
	}

 

    private void increamentSplit() {
        splitPointer++;
        if(splitPointer==hash1) {
            hash1 = hash2;
            hash2 = 2*hash1;
            splitPointer=0;
        }
        setTotalTuplesAndThreshold();
    }
    
    private void decrementSplit() {
    	if (splitPointer == 0) {
			hash2 = hash1;
			hash1 = hash2 / 2;
			splitPointer = hash1;
		}
    	splitPointer--;
        setTotalTuplesAndThreshold();
    }

    private void setTotalTuplesAndThreshold() {
        totalTuples = number_of_tuples_in_a_page*(hash1+splitPointer);
        setHighThreshold();
        setLowThreshold();
    }
    
    private void setHighThreshold() {
        tuple_threshold = (GlobalConst.MAX_PAGE_UTILIZATION*totalTuples)/100;
    }
    
    private void setLowThreshold() {
    	lower_threshold = (GlobalConst.MIN_HASH_UTILIZATION*totalTuples)/100;
    }

    private int calculateHashValueForTuple(Tuple t,boolean reHash) throws Exception{
		int hashValue = -1;
		switch(_in[indexField-1].attrType) {
			case AttrType.attrInteger:
				hashValue = calculateHash(t.getIntFld(indexField),reHash);
				break;
			case AttrType.attrString:
				hashValue = calculateHash(t.getStrFld(indexField),reHash);
				break;
			case AttrType.attrReal:
				hashValue = calculateHash(t.getFloFld(indexField), reHash);
				break;
			default:
				break;
		}
		return hashValue;
	}
    
    private int calculateHashValueFromHashTuple(Tuple t,boolean reHash) throws Exception{
  		int hashValue = -1;
  		switch(_in[indexField-1].attrType) {
  			case AttrType.attrInteger:
  				hashValue = calculateHash(t.getIntFld(1),reHash);
  				break;
  			case AttrType.attrString:
  				hashValue = calculateHash(t.getStrFld(1),reHash);
  				break;
  			case AttrType.attrReal:
  				hashValue = calculateHash(t.getFloFld(1), reHash);
  				break;
  			default:
  				break;
  		}
  		return hashValue;
  	}

    private int calculateHash(String data,boolean reHash) {
		long hash = 7;
		for (int i = 0; i < data.length(); i++) {
		    hash = hash*11 + data.charAt(i);
		}
		if(!reHash)
			return (int)(hash%hash1);
		return (int)(hash%hash2);
	}
    
	private int calculateHash(float data,boolean reHash) {
		if(!reHash)
			return ((int)(data*100))%hash1;
		return ((int)data*100)%hash2;
	}
	
	private int calculateHash(int data,boolean reHash) {
		if(!reHash)
			return data%hash1;
		return data%hash2;
	}
	
	private RID ridFromTuple(Tuple t1) throws Exception {
	    try {
	      t1.setHdr((short) 2, keyPageAttr, null);
	    } catch (Exception e) {
	      System.err.println("*** error in Tuple.setHdr() ***");
	      e.printStackTrace();
	    }
	    RID rid = new RID();
	    rid.slotNo = t1.getIntFld(2);
		rid.pageNo = new PageId(t1.getIntFld(1));
		return rid;
	}
	
	private Tuple tupleFromRid(RID rid) throws Exception {
	    Tuple t = new Tuple();
	    try {
	      t.setHdr((short) 2, keyPageAttr, null);
	    } catch (Exception e) {
	      System.err.println("*** error in Tuple.setHdr() ***");
	      e.printStackTrace();
	    }
	
	    int size = t.size();
	    t = new Tuple(size);
	    try {
	      t.setHdr((short) 2, keyPageAttr, null);
	    } catch (Exception e) {
	      System.err.println("*** error in Tuple.setHdr() ***");
	      e.printStackTrace();
	    }
	    t.setIntFld(1, rid.pageNo.pid);
	    t.setIntFld(2, rid.slotNo);
	    return t;
	}
    
    private Tuple keyTupleFromTuple(Tuple t1,int pid) throws Exception{
		Tuple t = new Tuple();
		t.setHdr((short)2, keyFileAttr, keyFileStrLens);
		t = new Tuple(t.size());
		t.setHdr((short)2, keyFileAttr, keyFileStrLens);
		switch(_in[indexField-1].attrType) {
		case AttrType.attrInteger:
			t.setIntFld(1, t1.getIntFld(indexField));
			break;
		case AttrType.attrString:
			t.setStrFld(1, t1.getStrFld(indexField));
			break;
		case AttrType.attrReal:
			t.setFloFld(1, t1.getFloFld(indexField));
			break;
		}
		t.setIntFld(2, pid);
		return t;
	}
    /**
	   * short cut to access the pinPage function in bufmgr package.
	   * @see bufmgr.pinPage
	   */
	  private void pinPage(PageId pageno, Page page, boolean emptyPage)
	    throws HFBufMgrException {
	    
	    try {
	      SystemDefs.JavabaseBM.pinPage(pageno, page, emptyPage);
	    }
	    catch (Exception e) {
	      throw new HFBufMgrException(e,"Heapfile.java: pinPage() failed");
	    }
	    
	  } // end of pinPage

	  /**
	   * short cut to access the unpinPage function in bufmgr package.
	   * @see bufmgr.unpinPage
	   */
	  private void unpinPage(PageId pageno, boolean dirty)
	    throws HFBufMgrException {

	    try {
	      SystemDefs.JavabaseBM.unpinPage(pageno, dirty);
	    }
	    catch (Exception e) {
	      throw new HFBufMgrException(e,"Heapfile.java: unpinPage() failed");
	    }

	  } // end of unpinPage

	  private PageId newPage(Page page, int num)
	    throws HFBufMgrException {

	    PageId tmpId = new PageId();

	    try {
	      tmpId = SystemDefs.JavabaseBM.newPage(page,num);
	     }
	    catch (Exception e) {
	      throw new HFBufMgrException(e,"Heapfile.java: newPage() failed");
	    }

	    return tmpId;

	  }
	  
	  private void freePage(PageId pageno) throws HFBufMgrException {

			try {
				SystemDefs.JavabaseBM.freePage(pageno);
			} catch (Exception e) {
				throw new HFBufMgrException(e, "Heapfile.java: freePage() failed");
			}

		} // end of freePage
}
