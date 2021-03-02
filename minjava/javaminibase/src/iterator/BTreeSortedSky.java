package iterator;

import java.io.IOException;

import bufmgr.PageNotReadException;
import global.AttrType;
import global.IndexType;
import heap.Heapfile;
import heap.InvalidTupleSizeException;
import heap.InvalidTypeException;
import heap.Tuple;
import index.IndexException;
import index.IndexScan;
import index.UnknownIndexTypeException;

public class BTreeSortedSky extends Iterator {

	
	private AttrType[] in1;
	private short col_len;
	private short[] str_sizes;
	private Iterator scan;
	private FldSpec[] Sprojection;
	private OBufSortSky oBuf;
	private int number_of_run = 0;


	public BTreeSortedSky(AttrType[] in1, short len_in1, short[] t1_str_sizes, Iterator am1, String relationName,
			int[] pref_list, int pref_list_length, String index_file, int n_pages) throws IndexException,
			InvalidTypeException, InvalidTupleSizeException, UnknownIndexTypeException, IOException, SortException {

		this.in1 = in1;
		col_len = len_in1;
		str_sizes = t1_str_sizes;

		oBuf = new OBufSortSky(in1, len_in1, t1_str_sizes, am1, relationName, pref_list, pref_list_length, n_pages);

		Sprojection = new FldSpec[len_in1];

		for (int i = 0; i < len_in1; i++) {
			Sprojection[i] = new FldSpec(new RelSpec(RelSpec.outer), i + 1);
		}

		scan = new IndexScan(new IndexType(IndexType.B_Index), relationName, index_file, this.in1, str_sizes, col_len,
				col_len, Sprojection, null, 0, false);

	}

	@Override
	public Tuple get_next() throws IOException, JoinsException, IndexException, InvalidTupleSizeException,
			InvalidTypeException, PageNotReadException, TupleUtilsException, PredEvalException, SortException,
			LowMemException, UnknowAttrType, UnknownKeyTypeException, Exception {
		// TODO Auto-generated method stub
		Tuple t;

		while ((t = scan.get_next()) != null) {
			if (oBuf.checkIfSky(t)) {
				return oBuf.Put(t);
			}
		}
		if (oBuf.isFlag()) {
			scan.close();
			scan = new FileScan(oBuf.getCurr_file() + number_of_run, in1, str_sizes, col_len, col_len, Sprojection, null);
			if (number_of_run > 0) {
				new Heapfile(oBuf.getCurr_file() + (number_of_run - 1)).deleteFile();
			}
			number_of_run++;
			oBuf.setNumber_of_window_file(oBuf.getNumber_of_window_file()+1);
			oBuf.setFlag(false);
			oBuf.init();
			return get_next();
		}
		return null;
	}

	@Override
	public void close() throws IOException, JoinsException, SortException, IndexException {
		// TODO Auto-generated method stub
		scan.close();
		try {
			new Heapfile(oBuf.getCurr_file() + (number_of_run - 1)).deleteFile();
		} catch (Exception e) {

		}
	}

}


