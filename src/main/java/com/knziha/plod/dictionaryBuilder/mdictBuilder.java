package com.knziha.plod.dictionaryBuilder;

import java.io.*;
import java.io.ByteArrayOutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.zip.Deflater;

import org.anarres.lzo.LzoCompressor1x_1;
import org.anarres.lzo.lzo_uintp;
import org.apache.commons.text.StringEscapeUtils;

import com.knziha.plod.dictionary.Utils.key_info_struct;
import com.knziha.plod.dictionary.mdict;
import com.knziha.plod.dictionary.Utils.myCpr;
import com.knziha.plod.dictionary.Utils.record_info_struct;
import com.knziha.plod.dictionary.Utils.BU;
import com.knziha.plod.dictionary.Utils.IU;
import com.knziha.rbtree.RBTNode;
import com.knziha.rbtree.RBTree_duplicative.inOrderDo;

import test.CMN;

/**
 * @author KnIfER
 * @date 2018/05/31
 */

public class mdictBuilder{
	private String _Dictionary_Name;
	private String _about;
	String _encoding;
	private int _encrypt=0;
	private int globalCompressionType=1;
	public String _stylesheet = "";
	private boolean isKeyCaseSensitive=false;
	private boolean isStripKey=true;
	private long _num_entries;public long getNumberEntries(){return _num_entries;}
	private long _num_key_blocks;
	private long _num_record_blocks;

	private final static String emptyStr = "";

	private key_info_struct[] _key_block_info_list;

	public ArrayListTree<myCprKey<String>> data_tree;

	public IntervalTree privateZone;

	public mdictBuilder(String Dictionary_Name,
			String about,
			String charset
			) {
		data_tree= new ArrayListTree<>();
		privateZone = new IntervalTree();
		_Dictionary_Name=Dictionary_Name;
		_about=StringEscapeUtils.escapeHtml4(about);
		_encoding=charset;
	}

	public int insert(String key,String data) {
		data_tree.insert(new myCprKey<>(key,data));
		return 0;
	}

	private final String nullStr=null;

	public HashMap<String,File> fileTree = new HashMap<>();
	public void insert(String key, File file) {
		data_tree.insert(new myCprKey<>(key,nullStr));
		fileTree.put(key, file);
	}
	public void recordFile(String key,File file) {
		fileTree.put(key, file);
	}

	public HashMap<String,ArrayList<myCprKey<String>>> bookTree = new HashMap<>();
	public void insert(String key, ArrayList<myCprKey<String>> bioc) {
		data_tree.insert(new myCprKey<>(key+"[<>]",nullStr));
		bookTree.put(key, bioc);
	}
	public void append(String key, File inhtml) {
		data_tree.add(new myCprKey<>(key,nullStr));
		fileTree.put(key, inhtml);
	}

	private String constructHeader() {
		String encoding = _encoding;
		if(encoding.equals("UTF-16LE"))
			encoding = "UTF-16"; //INCONGRUENT java charset
		if (encoding.equals(""))
			encoding = "UTF-8";
		float _version = 2.0f;
		SimpleDateFormat timemachine = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
		StringBuilder sb = new StringBuilder().append(new String(new byte[] {(byte) 0xff,(byte) 0xfe}, StandardCharsets.UTF_16LE))
				.append("<Dictionary GeneratedByEngineVersion=")//v
				.append("\"").append(_version).append("\"")
				.append(" CreationDate=")//v
				.append("\"").append(timemachine.format(new Date(System.currentTimeMillis()))).append("\"")
				.append(" RequiredEngineVersion=")//v
				.append("\"").append(_version).append("\"")
				.append(" Encrypted=")
				.append("\"").append("0").append("\"")//is NO valid?
				.append(" Encoding=")
				.append("\"").append(encoding).append("\"")
				.append(" Format=")//Format
				.append("\"").append("Html").append("\"")
				.append(" Compact=")//c
				.append("\"").append("Yes").append("\"")
				.append(" KeyCaseSensitive=")//k
				.append("\"").append(isKeyCaseSensitive?"Yes":"No").append("\"")
				.append(" StripKey=")//k
				.append("\"").append(isStripKey?"Yes":"No").append("\"")
				.append(" Description=")
				.append("\"").append(_about).append("\"")
				.append(sharedMdd!=null?" SharedMdd=\""+sharedMdd+"\"":"")
				.append(" Title=")
				.append("\"").append(_Dictionary_Name).append("\"")
				.append(" StyleSheet=")
				.append("\"").append(_stylesheet).append("\"")
				.append("/>");
		return sb.toString();
	}

	public void write(String path) throws IOException {
		File dirP = new File(path).getParentFile();
		dirP.mkdirs();
		if(!dirP.exists() && dirP.isDirectory())
			throw new IOException("input path invalid");

		RandomAccessFile fOut = new RandomAccessFile(path, "rw");
		//![1] write_header
		byte[] header_bytes = constructHeader().getBytes(StandardCharsets.UTF_16LE);
		fOut.writeInt(header_bytes.length);
		fOut.write(header_bytes);
		fOut.write(BU.toLH(BU.calcChecksum(header_bytes)));

		//![2]split Keys
		splitKeys(null);

		//![3] key block info
		long current=fOut.getFilePointer();
		writebBeforeKeyEntity(fOut);
		fOut.write(KeyBlockInfoData);

		splitKeys(fOut);

		long next=fOut.getFilePointer();
		fOut.seek(current);
		writebBeforeKeyEntity(fOut);
		fOut.seek(next);

		//![4] Encoding_record_block_header
		/*numer of record blocks*/
		fOut.writeLong(blockDataInfo_L.length);
		fOut.writeLong(_num_entries);
		/*numer of record blocks' info size*/
		fOut.writeLong(16*blockDataInfo_L.length);
		current = fOut.getFilePointer();
		fOut.skipBytes(8+blockInfo_L.length*16);

		//![5] 写入内容
		ArrayList<record_info_struct> eu_RecordblockInfo = new ArrayList<>(blockInfo_L.length);
		int baseCounter=0;
		int cc=0;
		for(int blockInfo_L_I:blockInfo_L) {
			CMN.show("writing recording block No."+(cc++));
			//写入记录块
			record_info_struct RinfoI = new record_info_struct();
			ByteArrayOutputStream data_raw = new ByteArrayOutputStream();
			//dict=new int[102400];
			//CMN.show(blockInfo_L[i]+":"+values.length);
			for(int entryC=0;entryC<blockInfo_L_I;entryC++) {//压入内容
				byte[] byteContent;
				if(values[baseCounter+entryC]==null) {
					File inhtml = fileTree.get(keys[baseCounter+entryC]);
					FileInputStream FIN = new FileInputStream(inhtml);
					byteContent = new byte[(int) inhtml.length()];
					FIN.read(byteContent);
					FIN.close();
				}else
					byteContent = values[baseCounter+entryC].getBytes(_encoding);
				data_raw.write(byteContent);
				data_raw.write(new byte[] {0x0d,0x0a,0});//xxx
			}

			byte[] data_raw_out = data_raw.toByteArray();
			RinfoI.decompressed_size = data_raw_out.length;

			if(globalCompressionType==1) {
				fOut.write(new byte[]{1,0,0,0});
				int in_len = data_raw_out.length;
				int out_len_preEmpt =  (in_len + in_len / 16 + 64+ 3);//xxx
				byte[] record_block_data = new byte[out_len_preEmpt];
				lzo_uintp out_len = new lzo_uintp();
				new LzoCompressor1x_1().compress(data_raw_out, 0, in_len, record_block_data, 0, out_len);
				//xxx
				//CMN.show(BU.calcChecksum(data_raw_out,0,(int) RinfoI.decompressed_size)+"asdasd");
				RinfoI.compressed_size = out_len.value;
				fOut.writeInt(BU.calcChecksum(data_raw_out,0,(int) RinfoI.decompressed_size));
				fOut.write(record_block_data,0,out_len.value);
			}
			else if(globalCompressionType==2) {
				fOut.write(new byte[]{2,0,0,0});

				byte[] buffer = new byte[1024];
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				Deflater df = new Deflater();
				df.setInput(data_raw_out, 0, (int) RinfoI.decompressed_size);

				df.finish();
				//ripemd128.printBytes(raw_data.array(),0, raw_data.position());
				//KeyBlockInfoDataLN = df.deflate(KeyBlockInfoData);
				while (!df.finished()) {
					  int n1 = df.deflate(buffer);
					  baos.write(buffer, 0, n1);
				}
				RinfoI.compressed_size = baos.size();
				fOut.writeInt(BU.calcChecksum(data_raw_out,0,(int) RinfoI.decompressed_size));
				fOut.write(baos.toByteArray());
			}
			baseCounter+=blockInfo_L_I;
			eu_RecordblockInfo.add(RinfoI);
		}


		next=fOut.getFilePointer();
		fOut.seek(current);
		/*numer of record blocks' size*/
		fOut.writeLong(next-current);
		/*record block infos*/
		for(record_info_struct RinfoI:eu_RecordblockInfo) {
			fOut.writeLong(RinfoI.compressed_size+8);//INCONGRUNENTSVG unmarked
			fOut.writeLong(RinfoI.decompressed_size);//!!!INCONGRUNENTSVG unmarked
		}

		//![5] 完成
		fOut.setLength(next);
		fOut.close();
	}

	private void writebBeforeKeyEntity(RandomAccessFile fOut) throws IOException {
		ByteBuffer sf = ByteBuffer.wrap(new byte[5*8]);

		/*number of keyblock count*/
		sf.putLong(_key_block_info_list.length);
		/*number of entries count*/
		sf.putLong(_num_entries);

		constructKeyBlockInfoData();

		/*number of bytes of deccompressed key block info data*/
		sf.putLong(KeyBlockInfoDataLN2);
		/*number of bytes of key block info*/
		sf.putLong(KeyBlockInfoDataLN+4*2);
		/*number of bytes of key block*/
		sf.putLong(key_block_compressed_size_accumulator+8*_key_block_info_list.length);
		byte[] five_Number_Bytes = sf.array();

		//CMN.show("key_block_info_size="+(KeyBlockInfoDataLN+4*2));
		//CMN.show("key_block_info_decomp_size="+KeyBlockInfoDataLN2);

		fOut.write(five_Number_Bytes);
		fOut.writeInt(BU.calcChecksum(five_Number_Bytes));

		fOut.write(new byte[]{2,0,0,0});
		fOut.writeInt(KeyBlockInfoDataDeCompressedAlder32);//BU.calcChecksum(KeyBlockInfoData,0,(int) KeyBlockInfoDataLN)+4*2);
	}


	byte[] KeyBlockInfoData;
	long KeyBlockInfoDataLN;
	long KeyBlockInfoDataLN2;
	int KeyBlockInfoDataDeCompressedAlder32;


	private void constructKeyBlockInfoData() throws UnsupportedEncodingException {
		ByteBuffer raw_data = ByteBuffer.wrap(new byte[_key_block_info_list.length*(8+(65535+2+2)*2+8*2)]);//INCONGRUENTSVG::3 not dyed version diff,interval not marked.
		for(key_info_struct infoI:_key_block_info_list) {
			raw_data.putLong(infoI.num_entries);
			byte[] hTextArray = infoI.headerKeyText;
			//CMN.show(hTextArray.length+"");
			raw_data.putChar((char) (_encoding.startsWith("UTF-16")?hTextArray.length/2:hTextArray.length));//TODO recollate
			raw_data.put(hTextArray);
			hTextArray = infoI.tailerKeyText;
				if(!_encoding.startsWith("UTF-16")){
					raw_data.put(new byte[] {0});
				}else{
					raw_data.put(new byte[] {0,0});
				}
			raw_data.putChar((char) (_encoding.startsWith("UTF-16")?hTextArray.length/2:hTextArray.length));
			raw_data.put(hTextArray);
				if(!_encoding.startsWith("UTF-16")){
					raw_data.put(new byte[] {0});
				}else{
					raw_data.put(new byte[] {0,0});
				}
			raw_data.putLong(infoI.key_block_compressed_size+8);//INCONGRUENTSVG this val is faked
			raw_data.putLong(infoI.key_block_decompressed_size);
		}
		KeyBlockInfoDataLN2 = raw_data.position();

		byte[] buffer = new byte[1024];
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Deflater df = new Deflater();
		df.setInput(raw_data.array(), 0, raw_data.position());
		KeyBlockInfoDataDeCompressedAlder32 = BU.calcChecksum(raw_data.array(), 0, raw_data.position());
		df.finish();
		//ripemd128.printBytes(raw_data.array(),0, raw_data.position());
		//KeyBlockInfoDataLN = df.deflate(KeyBlockInfoData);
		while (!df.finished()) {
		  int n = df.deflate(buffer);
		  baos.write(buffer, 0, n);
		}
		KeyBlockInfoData = baos.toByteArray();
		//ripemd128.printBytes(KeyBlockInfoData);
		KeyBlockInfoDataLN = KeyBlockInfoData.length;
		//ripemd128.printBytes(KeyBlockInfoData,0,(int) KeyBlockInfoDataLN);
		//byte[] key_block_info = BU.zlib_decompress(KeyBlockInfoData,0,(int) KeyBlockInfoDataLN);
		//ripemd128.printBytes(key_block_info);
	}


	int perKeyBlockSize_IE_IndexBlockSize = 32;
	int perRecordBlockSize = 32;
	long key_block_compressed_size_accumulator;
	long record_block_decompressed_size_accumulator;

	int [] offsets;
	String[] values,keys;
	Integer[] blockDataInfo_L;
	Integer[] blockInfo_L;

	private void splitKeys(RandomAccessFile fOutTmp) throws IOException {
		final ArrayList<String> keyslist = new ArrayList<>();
		final ArrayList<String> valslist = new ArrayList<>();
		data_tree.SetInOrderDo(new inOrderDo() {
			@Override
			public void dothis(RBTNode node) {
				String key = ((myCprKey)node.getKey()).key;
				String val = (String) ((myCprKey)node.getKey()).value;
				keyslist.add(key);
				valslist.add(val);
		}});
		data_tree.inOrderDo();

		for(int i=0;i<keyslist.size();i++) {//扩充
			String key = keyslist.get(i);
			if(key.endsWith("[<>]")) {
				keyslist.remove(i);
				valslist.remove(i);
				int start = i;
				String name=key.substring(0, key.length()-4);
				ArrayList<myCprKey<String>> bookc = bookTree.get(name);
				for(myCprKey<String> xx:bookc) {
					keyslist.add(i,xx.key);
					valslist.add(i++,xx.value);
				}
				if(bookc.size()>0) {
					i--;
					privateZone.addInterval(start, i, name);
					CMN.show(name+" added  "+start+" :: "+i);
				}
			}
		}

		long counter=_num_entries=keyslist.size();
		record_block_decompressed_size_accumulator=0;

		//calc record split
		offsets = new int[(int) _num_entries];
		values = valslist.toArray(new String[] {});
		keys = keyslist.toArray(new String[] {});

		//todo::more precise estimate
		ArrayList<Integer> blockInfo = new ArrayList<>((int)(_num_entries/2000));// number of bytes of all rec-blocks
		ArrayList<Integer> blockDataInfo = new ArrayList<>((int)(_num_entries/2000));// number of entries of all rec-blocks
		while(counter>0) {
			int idx = blockInfo.size();
			blockDataInfo.add(0);//byte数量
			blockInfo.add(0);//条目数量
			while(true) {
				if(counter<=0) break;
				int recordLen;
				int preJudge;
				//1st, pull data.
				if(values[(int) (_num_entries-counter)]!=null) {
					//从内存
					/* fetching record data from memory */
					byte[] record_data = values[(int) (_num_entries-counter)].getBytes(_encoding);
					recordLen = record_data.length;
					preJudge = blockDataInfo.get(idx)+recordLen;
				}else {
					/* fetching record data from file */
					File inhtml = fileTree.get(keys[(int) (_num_entries-counter)]);
					recordLen = (int) inhtml.length();
					preJudge = blockDataInfo.get(idx)+recordLen;
				}

				//2nd, judge & save data.
				if(preJudge<1024*perRecordBlockSize) {
					/* PASSING */
					offsets[(int) (_num_entries-counter)] = (int) record_block_decompressed_size_accumulator+3*((int) (_num_entries-counter));//xxx+3*((int) (_num_entries-counter));
					record_block_decompressed_size_accumulator+=recordLen;
					blockDataInfo.set(idx, preJudge);/*offset+=preJudge*/
					blockInfo.set(idx, blockInfo.get(idx)+1);/*entry++*/
					counter-=1;
				}
				else if(recordLen>=1024*perRecordBlockSize) {
					/* MONO OCCUPYING */
					offsets[(int) (_num_entries-counter)] = (int) record_block_decompressed_size_accumulator+3*((int) (_num_entries-counter));//xxx+3*((int) (_num_entries-counter));
					record_block_decompressed_size_accumulator+=recordLen;
					if(blockDataInfo.get(idx)!=0) {//新开一个recblock
						blockDataInfo.add(0);
						blockInfo.add(0);
						idx++;
					}
					blockDataInfo.set(idx, recordLen);/*offset+=preJudge*/  //+3*((int) (_num_entries-counter))
					blockInfo.set(idx, 1);/*entry++*/
					counter-=1;
					break;
				}
				else/* NOT PASSING */ break;
			}
		}
		blockDataInfo_L = blockDataInfo.toArray(new Integer[] {});
		blockInfo_L = blockInfo.toArray(new Integer[] {});

		//calc index split
		counter=_num_entries;
		ArrayList<key_info_struct> list = new ArrayList<>();
		key_block_compressed_size_accumulator=0;
		int sizeLimit = 1024 * perKeyBlockSize_IE_IndexBlockSize;
		ByteBuffer key_block_data_wrap = ByteBuffer.wrap(new byte[sizeLimit]);
		while(counter>0) {//总循环
			key_block_data_wrap.clear();
			key_info_struct infoI = new key_info_struct();
			//dict = new int[102400];//TODO reuse
			long number_entries_counter = 0;
			long baseCounter = _num_entries-counter;
			//if(_num_entries-counter==0) CMN.show("___ "+(privateZone.container(198).key));
			if(privateZone.container((int) (_num_entries-counter))!=null) {
				myCpr<Integer, Integer> interval = privateZone.container((int) (_num_entries-counter));
				//CMN.show(interval.key+" ~ "+interval.value+" via "+(int) (_num_entries-counter));
				for(int i=interval.key;i<=interval.value;i++) {
					//CMN.show("putting!.."+(_num_entries-counter));
					key_block_data_wrap.putLong(offsets[i]);//占位 offsets i.e. keyid
					key_block_data_wrap.put(keyslist.get(i).getBytes(_encoding));
					//CMN.show(number_entries_counter+":"+keyslist.get((int) (_num_entries-counter)));
					if(_encoding.startsWith("UTF-16")){
						key_block_data_wrap.put(new byte[]{0,0});//INCONGRUENTSVG
					}else
						key_block_data_wrap.put(new byte[]{0});//INCONGRUENTSVG
					number_entries_counter++;
					counter--;
				}
				infoI.num_entries = number_entries_counter;
				//CMN.show(baseCounter+":"+number_entries_counter+":"+keyslist.size());
				String whatever = privateZone.names.get(interval.key)+"";
				//whatever = keyslist.get(interval.key);
				infoI.headerKeyText = whatever.getBytes(_encoding);
				infoI.tailerKeyText = (whatever).getBytes(_encoding);
				CMN.show(whatever);
			}
			else {
				while(true) {//常规压入entries
					if(counter<=0) break;
					int retPos = key_block_data_wrap.position();
					try {//必定抛出，除非最后一个block.
						if(privateZone.container((int) (_num_entries-counter))!=null) throw new BufferOverflowException();
						key_block_data_wrap.putLong(offsets[(int) (_num_entries-counter)]);//占位 offsets i.e. keyid
						key_block_data_wrap.put(keyslist.get((int) (_num_entries-counter)).getBytes(_encoding));
						//CMN.show(number_entries_counter+":"+keyslist.get((int) (_num_entries-counter)));
						if(_encoding.startsWith("UTF-16")){
							key_block_data_wrap.put(new byte[]{0,0});//INCONGRUENTSVG
						}else
							key_block_data_wrap.put(new byte[]{0});//INCONGRUENTSVG
						counter-=1;
						number_entries_counter+=1;//完整放入后才计数
						//key_block_data.put(new byte[]{0});
					} catch (BufferOverflowException e) {
						//e.printStackTrace();
						key_block_data_wrap.position(retPos);//不完整放入则回退。
						break;
					}
				}
				infoI.num_entries = number_entries_counter;
				//CMN.show(baseCounter+":"+number_entries_counter+":"+keyslist.size());
				infoI.headerKeyText = keyslist.get((int) baseCounter).toLowerCase().replace(" ",emptyStr).replace("-",emptyStr).getBytes(_encoding);
				infoI.tailerKeyText = keyslist.get((int) (baseCounter+number_entries_counter-1)).toLowerCase().replace(" ",emptyStr).replace("-",emptyStr).getBytes(_encoding);
			}
			infoI.key_block_decompressed_size = key_block_data_wrap.position();
			int in_len = (int) infoI.key_block_decompressed_size;
			byte[] key_block_data = key_block_data_wrap.array();


			if(globalCompressionType==1) {//lzo压缩全部
				if(fOutTmp!=null) fOutTmp.write(new byte[]{1,0,0,0});
				//fOut.write(new byte[] {0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,9,9,9,9,9});
				int out_len_preEmpt =  (in_len + in_len / 16 + 64 + 3);//
				byte[] compressed_key_block_data = new byte[out_len_preEmpt];

				if(fOutTmp!=null) fOutTmp.writeInt(BU.calcChecksum(key_block_data,0,(int) infoI.key_block_decompressed_size));
				/*MInt out_len = new MInt();
				//CMN.show(":"+in_len+":"+out_len_preEmpt); 字典太小会抛出
				MiniLZO.lzo1x_1_compress(key_block_data, in_len, compressed_key_block_data, out_len, dict);*/

				lzo_uintp out_len = new lzo_uintp();
				new LzoCompressor1x_1().compress(key_block_data, 0, in_len, compressed_key_block_data, 0, out_len);

				infoI.key_block_compressed_size = out_len.value;
				if(fOutTmp!=null) fOutTmp.write(compressed_key_block_data,0,out_len.value);
			}
			else if(globalCompressionType==2) {//zip
				if(fOutTmp!=null) fOutTmp.write(new byte[]{2,0,0,0});
				byte[] buffer = new byte[1024];
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				Deflater df = new Deflater();
				df.setInput(key_block_data, 0, in_len);

				df.finish();
				//ripemd128.printBytes(raw_data.array(),0, raw_data.position());
				//KeyBlockInfoDataLN = df.deflate(KeyBlockInfoData);

				while (!df.finished()) {
					  int n1 = df.deflate(buffer);
					  baos.write(buffer, 0, n1);
				}
				infoI.key_block_compressed_size = baos.size();
				if(fOutTmp!=null) fOutTmp.writeInt(BU.calcChecksum(key_block_data,0,in_len));
				if(fOutTmp!=null) fOutTmp.write(baos.toByteArray());
			}

			//CMN.show("infoI key_block_data raw");
			//ripemd128.printBytes(key_block_data.array(),0, (int) infoI.key_block_decompressed_size);
			//CMN.show("infoI.key_block_data");
			//ripemd128.printBytes(infoI.key_block_data,0,(int) infoI.key_block_compressed_size);
			//CMN.show(infoI.key_block_decompressed_size+"~~"+infoI.key_block_compressed_size);
			/*
			byte[] key_block_data_return = new byte[(int) infoI.key_block_decompressed_size];
			MInt len = new MInt((int) infoI.key_block_decompressed_size);
			MiniLZO.lzo1x_decompress(infoI.key_block_data,(int) infoI.key_block_compressed_size,key_block_data_return,len);
			CMN.show("key_block_data_return");
			ripemd128.printBytes(key_block_data_return);
			*/
			list.add(infoI);
			key_block_compressed_size_accumulator += infoI.key_block_compressed_size;
		}
		//CMN.show("le"+list.size());
		_key_block_info_list = list.toArray(new key_info_struct[] {});
	}

	private int bookGetRecordLen(String key) {
		int len =0;
		ArrayList<myCprKey<String>> bookc = bookTree.get(key.substring(0, key.length()-4));
		for(myCprKey<String> xx:bookc) {
			if(xx.value!=null)
				try {
					len+=xx.value.getBytes(_encoding).length;
				} catch (UnsupportedEncodingException e) {
					e.printStackTrace();
				}
			else
				len+=fileTree.get(xx.key).length();
		}
		return len;
	}

	private int bookGetNumKeys(String key) {
		ArrayList<myCprKey<String>> bookc = bookTree.get(key.substring(0, key.length()-4));
		return bookc.size();
	}

	public void setZLibCompress(boolean flag) {
		if(flag) {
			globalCompressionType =2;
		}else {
			globalCompressionType =1;
		}
	}

	public void setRecordUnitSize(int val) {
		perRecordBlockSize=val;
	}
	public void setIndexUnitSize(int val) {
		perKeyBlockSize_IE_IndexBlockSize=val;
	}

	public int getCountOf(String key) {
		return data_tree.getCountOf(new myCprKey<>(key,""));
	}

	String sharedMdd;
	public void setSharedMdd(String name) {
		sharedMdd=name;
	}


	public void setKeycaseSensitive(boolean b) {
		isKeyCaseSensitive=b;
	}

	protected String mOldSchoolToLowerCase(String input) {
		StringBuilder sb = new StringBuilder(input);
		for(int i=0;i<sb.length();i++) {
			if(sb.charAt(i)>='A' && sb.charAt(i)<='Z')
				sb.setCharAt(i, (char) (sb.charAt(i)+32));
		}
		return sb.toString();
	}
	protected String processMyText(String input) {
		String ret = isStripKey?mdict.replaceReg.matcher(input).replaceAll(emptyStr):input;
		return isKeyCaseSensitive?ret:ret.toLowerCase();
	}

	public class myCprKey<T2> implements Comparable<myCprKey<T2>>{
		public String key;
		public T2 value;
		public myCprKey(String vk,T2 v){
			key=vk;value=v;
		}
		public int compareTo(myCprKey<T2> other) {
			if(key.endsWith(">") && other.key.endsWith(">")) {
				int idx2 = key.lastIndexOf("<",key.length()-2);
				if(idx2!=-1) {
					int idx3 = other.key.lastIndexOf("<",key.length()-2);
					if(idx3!=-1) {
						if(key.startsWith(other.key.substring(0,idx3))) {
							String itemA=key.substring(idx2+1,key.length()-1);
							String itemB=other.key.substring(idx2+1,other.key.length()-1);
							idx2=-1;idx3=-1;
							if(IU.shuzi.matcher(itemA).find()) {
								idx2=IU.parsint(itemA);
							}else if(IU.hanshuzi.matcher(itemA).find()) {
								idx2=IU.recurse1wCalc(itemA, 0, itemA.length()-1, 1);
							}
							if(idx2!=-1) {
								if(IU.shuzi.matcher(itemB).find()) {
									idx3=IU.parsint(itemB);
								}else if(IU.hanshuzi.matcher(itemB).find()) {
									idx3=IU.recurse1wCalc(itemB, 0, itemB.length()-1, 1);
								}
								if(idx3!=-1)
									return idx2-idx3;
							}

						}
					}
				}
			}
			return (processMyText(key).compareTo(processMyText(other.key)));
		}
		public String toString(){
			return key+"_"+value;
		}
	}

}