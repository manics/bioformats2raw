/**
 * Copyright (c) 2019 Glencoe Software, Inc. All rights reserved.
 *
 * This software is distributed under the terms described by the LICENSE.txt
 * file you can find at the root of the distribution bundle.  If the file is
 * missing please request a copy by contacting info@glencoesoftware.com
 */
package com.glencoesoftware.bioformats2raw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import loci.common.Constants;
import loci.common.image.IImageScaler;
import loci.common.image.SimpleImageScaler;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.ChannelSeparator;
import loci.formats.ClassList;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.ImageWriter;
import loci.formats.MetadataTools;
import loci.formats.MissingLibraryException;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import loci.formats.services.OMEXMLServiceImpl;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.EnumerationException;

import org.janelia.saalfeldlab.n5.ByteArrayDataBlock;
import org.janelia.saalfeldlab.n5.Bzip2Compression;
import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.Lz4Compression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrReader;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.ShortArrayDataBlock;
import org.janelia.saalfeldlab.n5.XzCompression;
import org.janelia.saalfeldlab.n5.blosc.BloscCompression;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.glencoesoftware.bioformats2raw.MiraxReader.TilePointer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import ch.qos.logback.classic.Level;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command line tool for converting whole slide imaging files to N5.
 */
public class Converter implements Callable<Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(Converter.class);

  /**
   * Minimum size of the largest XY dimension in the smallest resolution,
   * when calculating the number of resolutions to generate.
   */
  private static final int MIN_SIZE = 256;

  /** Scaling factor in X and Y between any two consecutive resolutions. */
  private static final int PYRAMID_SCALE = 2;

  /** Version of the bioformats2raw layout. */
  public static final Integer LAYOUT = 1;

  /** Enumeration that backs the --file_type flag. Instances can be used
   * as a factory method to create {@link N5Reader} and {@link N5Writer}
   * instances.
   */
  enum FileType {
    n5 {
      N5Reader reader(String path) throws IOException {
        return new N5FSReader(path);
      }
      N5Writer writer(String path) throws IOException {
        return new N5FSWriter(path);
      }
    },
    zarr {
      N5Reader reader(String path) throws IOException {
        return new N5ZarrReader(path);
      }
      N5Writer writer(String path) throws IOException {
        return new N5ZarrWriter(path);
      }
    };
    abstract N5Reader reader(String path) throws IOException;
    abstract N5Writer writer(String path) throws IOException;
  }

  static class N5Compression {
    enum CompressionTypes { blosc, bzip2, gzip, lz4, raw, xz };

    private static Compression getCompressor(
            CompressionTypes type,
            Integer compressionParameter)
    {
      switch (type) {
        case blosc:
          return new BloscCompression(
                  "lz4",
                  5, // clevel
                  BloscCompression.SHUFFLE,  // shuffle
                  0, // blocksize (0 = auto)
                  1  // nthreads
          );
        case gzip:
          if (compressionParameter == null) {
            return new GzipCompression();
          }
          else {
            return new GzipCompression(compressionParameter.intValue());
          }
        case bzip2:
          if (compressionParameter == null) {
            return new Bzip2Compression();
          }
          else {
            return new Bzip2Compression(compressionParameter.intValue());
          }
        case xz:
          if (compressionParameter == null) {
            return new XzCompression();
          }
          else {
            return new XzCompression(compressionParameter.intValue());
          }
        case lz4:
          if (compressionParameter == null) {
            return new Lz4Compression();
          }
          else {
            return new Lz4Compression(compressionParameter.intValue());
          }
        case raw:
          return new RawCompression();
        default:
          return null;
      }
    }
  }

  @Parameters(
    index = "0",
    arity = "1",
    description = "file to convert"
  )
  private volatile Path inputPath;

  @Parameters(
    index = "1",
    arity = "1",
    description = "path to the output pyramid directory"
  )
  private volatile Path outputPath;

  @Option(
    names = {"-r", "--resolutions"},
    description = "Number of pyramid resolutions to generate"
  )
  private volatile Integer pyramidResolutions;

  @Option(
    names = {"-w", "--tile_width"},
    description = "Maximum tile width to read (default: ${DEFAULT-VALUE})"
  )
  private volatile int tileWidth = 1024;

  @Option(
    names = {"-h", "--tile_height"},
    description = "Maximum tile height to read (default: ${DEFAULT-VALUE})"
  )
  private volatile int tileHeight = 1024;

  @Option(
    names = "--debug",
    description = "Turn on debug logging"
  )
  private volatile boolean debug = false;

  @Option(
    names = "--max_workers",
    description = "Maximum number of workers (default: ${DEFAULT-VALUE})"
  )
  private volatile int maxWorkers = Runtime.getRuntime().availableProcessors();

  @Option(
    names = "--max_cached_tiles",
    description =
      "Maximum number of tiles that will be cached across all "
      + "workers (default: ${DEFAULT-VALUE})"
  )
  private volatile int maxCachedTiles = 64;

  @Option(
          names = {"-c", "--compression"},
          description = "Compression type for n5 " +
                  "(${COMPLETION-CANDIDATES}; default: ${DEFAULT-VALUE})"
  )
  private volatile N5Compression.CompressionTypes compressionType =
          N5Compression.CompressionTypes.blosc;

  @Option(
          names = {"--compression-parameter"},
          description = "Integer parameter for chosen compression (see " +
                  "https://github.com/saalfeldlab/n5/blob/master/README.md" +
                  " )"
  )
  private volatile Integer compressionParameter = null;

  @Option(
          names = "--extra-readers",
          arity = "0..1",
          split = ",",
          description = "Separate set of readers to include; " +
                  "(default: ${DEFAULT-VALUE})"
  )
  private volatile Class<?>[] extraReaders = new Class[] {
    PyramidTiffReader.class, MiraxReader.class
  };

  @Option(
          names = "--file_type",
          description = "Tile file extension: ${COMPLETION-CANDIDATES} " +
                  "(default: ${DEFAULT-VALUE}) " +
                  "[Can break compatibility with raw2ometiff]"
  )
  private volatile FileType fileType = FileType.n5;

  @Option(
          names = "--pyramid-name",
          description = "Name of pyramid (default: ${DEFAULT-VALUE}) " +
                  "[Can break compatibility with raw2ometiff]"
  )
  private volatile String pyramidName = "data.n5";

  @Option(
          names = "--scale-format-string",
          description = "Format string for scale paths "+
                  "[Can break compatibility with raw2ometiff] " +
                  "(default: ${DEFAULT-VALUE})"
  )
  private volatile String scaleFormatString = "%d/%d";

  @Option(
          names = "--dimension-order",
          description = "Override the input file dimension order in the " +
                  "output file [Can break compatibility with raw2ometiff] " +
                  "(${COMPLETION-CANDIDATES})"
  )
  private volatile DimensionOrder dimensionOrder;

  /** Scaling implementation that will be used during downsampling. */
  private volatile IImageScaler scaler = new SimpleImageScaler();

  /**
   * Set of readers that can be used concurrently, size will be equal to
   * {@link #maxWorkers}.
   */
  private volatile BlockingQueue<IFormatReader> readers;

  /**
   * Bounded task queue limiting the number of in flight conversion operations
   * happening in parallel.  Size will be equal to {@link #maxWorkers}.
   */
  private volatile BlockingQueue<Runnable> queue;

  private volatile ExecutorService executor;

  /** Whether or not the source file is little endian. */
  private boolean isLittleEndian;

  /**
   * The source file's pixel type.  Retrieved from
   * {@link IFormatReader#getPixelType()}.
   */
  private volatile int pixelType;

  /** Total number of tiles at the current resolution during processing. */
  private volatile int tileCount;

  /** Current number of tiles processed at the current resolution. */
  private volatile AtomicInteger nTile;

  @Override
  public Void call() throws Exception {
    ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
        LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    if (debug) {
      root.setLevel(Level.DEBUG);
    }
    else {
      root.setLevel(Level.INFO);
    }
    readers = new ArrayBlockingQueue<IFormatReader>(maxWorkers);
    queue = new LimitedQueue<Runnable>(maxWorkers);
    executor = new ThreadPoolExecutor(
      maxWorkers, maxWorkers, 0L, TimeUnit.MILLISECONDS, queue);
    convert();
    return null;
  }

  /**
   * Perform the pyramid conversion according to the specified
   * command line arguments.
   *
   * @throws FormatException
   * @throws IOException
   * @throws InterruptedException
   * @throws EnumerationException
   */
  public void convert()
      throws FormatException, IOException, InterruptedException,
             EnumerationException
  {

    if (fileType.equals(FileType.zarr) && pyramidName.equals("data.n5")) {
      pyramidName = "data.zarr";
    }

    if (!pyramidName.equals("data.n5") ||
              !scaleFormatString.equals("%d/%d"))
    {
      LOGGER.info("Output will be incompatible with raw2ometiff " +
              "(pyramidName: {}, scaleFormatString: {})",
              pyramidName, scaleFormatString);
    }

    Cache<TilePointer, byte[]> tileCache = CacheBuilder.newBuilder()
        .maximumSize(maxCachedTiles)
        .build();

    // First find which reader class we need
    ClassList<IFormatReader> readerClasses =
        ImageReader.getDefaultReaderClasses();

    for (Class<?> reader : extraReaders) {
      readerClasses.addClass(0, (Class<IFormatReader>) reader);
      LOGGER.debug("Added extra reader: {}", reader);
    }

    ImageReader imageReader = new ImageReader(readerClasses);
    Class<?> readerClass;
    try {
      imageReader.setId(inputPath.toString());
      readerClass = imageReader.getReader().getClass();
    }
    finally {
      imageReader.close();
    }
    // Now with our found type instantiate our queue of readers for use
    // during conversion
    for (int i=0; i < maxWorkers; i++) {
      IFormatReader reader;
      try {
        reader = (IFormatReader) readerClass.getConstructor().newInstance();
      }
      catch (Exception e) {
        LOGGER.error("Failed to instantiate reader: {}", readerClass, e);
        return;
      }
      reader.setFlattenedResolutions(false);
      reader.setMetadataFiltered(true);
      reader.setMetadataStore(createMetadata());
      reader.setId(inputPath.toString());
      reader.setResolution(0);
      if (reader instanceof MiraxReader) {
        ((MiraxReader) reader).setTileCache(tileCache);
      }
      readers.add(new ChannelSeparator(reader));
    }

    // Finally, perform conversion on all series
    try {
      int seriesCount;
      IFormatReader v = readers.take();
      try {
        seriesCount = v.getSeriesCount();
        IMetadata meta = (IMetadata) v.getMetadataStore();

        for (int s=0; s<meta.getImageCount(); s++) {
          meta.setPixelsBigEndian(true, s);
        }

        String xml = getService().getOMEXML(meta);

        // write the original OME-XML to a file
        if (!Files.exists(outputPath)) {
          Files.createDirectories(outputPath);
        }
        Path omexmlFile = outputPath.resolve("METADATA.ome.xml");
        Files.write(omexmlFile, xml.getBytes(Constants.ENCODING));
      }
      catch (ServiceException se) {
        LOGGER.error("Could not retrieve OME-XML", se);
        return;
      }
      finally {
        readers.put(v);
      }

      for (int i=0; i<seriesCount; i++) {
        try {
          write(i);
        }
        catch (Throwable t) {
          LOGGER.error("Error while writing series {}", i, t);
          unwrapException(t);
          return;
        }
      }
    }
    finally {
      // Shut down first, tasks may still be running
      executor.shutdown();
      executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
      readers.forEach((v) -> {
        try {
          v.close();
        }
        catch (IOException e) {
          LOGGER.error("Exception while closing reader", e);
        }
      });
    }
  }

  /**
   * Convert the data specified by the given initialized reader to
   * an intermediate form.
   *
   * @param series the reader series index to be converted
   * @throws FormatException
   * @throws IOException
   * @throws InterruptedException
   * @throws EnumerationException
   */
  public void write(int series)
    throws FormatException, IOException, InterruptedException,
           EnumerationException
  {
    readers.forEach((reader) -> {
      reader.setSeries(series);
    });
    saveResolutions(series);
  }

  private byte[] getTileDownsampled(
      int series, int resolution, int plane, int xx, int yy,
      int width, int height)
          throws FormatException, IOException, InterruptedException,
                 EnumerationException
  {
    final String pathName = "/" +
            String.format(scaleFormatString, series, resolution - 1);
    final String pyramidPath = outputPath.resolve(pyramidName).toString();
    final N5Reader n5 = fileType.reader(pyramidPath);

    DatasetAttributes datasetAttributes = n5.getDatasetAttributes(pathName);
    long[] dimensions = datasetAttributes.getDimensions();
    int[] blockSizes = datasetAttributes.getBlockSize();
    int activeTileWidth = blockSizes[0];
    int activeTileHeight = blockSizes[1];

    // Upscale our base X and Y offsets, and sizes to the previous resolution
    // based on the pyramid scaling factor
    xx *= PYRAMID_SCALE;
    yy *= PYRAMID_SCALE;
    width = (int) Math.min(
        activeTileWidth * PYRAMID_SCALE, dimensions[0] - xx);
    height = (int) Math.min(
        activeTileHeight * PYRAMID_SCALE, dimensions[1] - yy);

    IFormatReader reader = readers.take();
    long[] startGridPosition;
    try {
      startGridPosition = getGridPosition(
        reader, xx / activeTileWidth, yy / activeTileHeight, plane);
    }
    finally {
      readers.put(reader);
    }
    int xBlocks = (int) Math.ceil((double) width / activeTileWidth);
    int yBlocks = (int) Math.ceil((double) height / activeTileHeight);

    int bytesPerPixel = FormatTools.getBytesPerPixel(pixelType);
    byte[] tile = new byte[width * height * bytesPerPixel];
    for (int xBlock=0; xBlock<xBlocks; xBlock++) {
      for (int yBlock=0; yBlock<yBlocks; yBlock++) {
        int blockWidth = Math.min(
          width - (xBlock * activeTileWidth), activeTileWidth);
        int blockHeight = Math.min(
          height - (yBlock * activeTileHeight), activeTileHeight);
        long[] gridPosition = new long[] {
          startGridPosition[0] + xBlock, startGridPosition[1] + yBlock,
          startGridPosition[2], startGridPosition[3], startGridPosition[4]
        };
        ByteBuffer subTile = n5.readBlock(
          pathName, datasetAttributes, gridPosition
        ).toByteBuffer();

        int length = blockWidth * bytesPerPixel;
        for (int y=0; y<blockHeight; y++) {
          int srcPos = y * length;
          int destPos = ((yBlock * width * activeTileHeight)
            + (y * width) + (xBlock * activeTileWidth)) * bytesPerPixel;
          subTile.position(srcPos);
          subTile.get(tile, destPos, length);
        }
      }
    }
    return scaler.downsample(tile, width, height,
        PYRAMID_SCALE, bytesPerPixel, false,
        FormatTools.isFloatingPoint(pixelType),
        1, false);
  }

  private byte[] getTile(
      int series, int resolution, int plane, int xx, int yy,
      int width, int height)
          throws FormatException, IOException, InterruptedException,
                 EnumerationException
  {
    if (resolution == 0) {
      IFormatReader reader = readers.take();
      try {
        return reader.openBytes(plane, xx, yy, width, height);
      }
      finally {
        readers.put(reader);
      }
    }
    else {
      Slf4JStopWatch t0 = new Slf4JStopWatch("getTileDownsampled");
      try {
        return getTileDownsampled(
            series, resolution, plane, xx, yy, width, height);
      }
      finally {
        t0.stop();
      }
    }
  }

  /**
   * Retrieve the dimensions based on either the configured or input file
   * dimension order at the current resolution.
   * @param reader initialized reader for the input file
   * @param scaledWidth size of the X dimension at the current resolution
   * @param scaledHeight size of the Y dimension at the current resolution
   * @return dimension array ready for use with N5
   * @throws EnumerationException
   */
  private long[] getDimensions(
    IFormatReader reader, int scaledWidth, int scaledHeight)
      throws EnumerationException
  {
    int sizeZ = reader.getSizeZ();
    int sizeC = reader.getSizeC();
    int sizeT = reader.getSizeT();
    String o = dimensionOrder != null? dimensionOrder.toString()
        : reader.getDimensionOrder();
    long[] dimensions = new long[] {scaledWidth, scaledHeight, 0, 0, 0};
    dimensions[o.indexOf("Z")] = sizeZ;
    dimensions[o.indexOf("C")] = sizeC;
    dimensions[o.indexOf("T")] = sizeT;
    return dimensions;
  }

  /**
   * Retrieve the grid position based on either the configured or input file
   * dimension order at the current resolution.
   * @param reader initialized reader for the input file
   * @param x X position at the current resolution
   * @param y Y position at the current resolution
   * @param plane current plane being operated upon
   * @return grid position array ready for use with N5
   * @throws EnumerationException
   */
  private long[] getGridPosition(
    IFormatReader reader, int x, int y, int plane) throws EnumerationException
  {
    String o = dimensionOrder != null? dimensionOrder.toString()
        : reader.getDimensionOrder();
    int[] zct = reader.getZCTCoords(plane);
    long[] gridPosition = new long[] {x, y, 0, 0, 0};
    gridPosition[o.indexOf("Z")] = zct[0];
    gridPosition[o.indexOf("C")] = zct[1];
    gridPosition[o.indexOf("T")] = zct[2];
    return gridPosition;
  }

  private void processTile(
      int series, int resolution, int plane, int xx, int yy,
      int width, int height)
        throws EnumerationException, FormatException, IOException,
          InterruptedException
  {
    String pathName =
        "/" + String.format(scaleFormatString, series, resolution);
    final String pyramidPath = outputPath.resolve(pyramidName).toString();
    final N5Writer n5 = fileType.writer(pyramidPath);
    DatasetAttributes datasetAttributes = n5.getDatasetAttributes(pathName);
    int[] blockSizes = datasetAttributes.getBlockSize();
    int activeTileWidth = blockSizes[0];
    int activeTileHeight = blockSizes[1];
    IFormatReader reader = readers.take();
    long[] gridPosition;
    try {
      gridPosition = getGridPosition(
          reader, xx / activeTileWidth, yy / activeTileHeight, plane);
    }
    finally {
      readers.put(reader);
    }
    int[] size = new int[] {width, height, 1, 1, 1};

    Slf4JStopWatch t0 = new Slf4JStopWatch("getTile");
    DataBlock<?> dataBlock;
    try {
      LOGGER.info("requesting tile to write at {} to {}",
        gridPosition, pathName);
      byte[] tile = getTile(series, resolution, plane, xx, yy, width, height);
      if (tile == null) {
        return;
      }

      int bytesPerPixel = FormatTools.getBytesPerPixel(pixelType);
      switch (bytesPerPixel) {
        case 1:
          dataBlock = new ByteArrayDataBlock(size, gridPosition, tile);
          break;
        case 2:
          short[] asShort = new short[tile.length / 2];
          ByteBuffer bb = ByteBuffer.wrap(tile);
          if (resolution == 0 && isLittleEndian) {
            bb = bb.order(ByteOrder.LITTLE_ENDIAN);
          }
          bb.asShortBuffer().get(asShort);
          dataBlock = new ShortArrayDataBlock(size, gridPosition, asShort);
          break;
        default:
          throw new FormatException(
              "Unsupported bytes per pixel: " + bytesPerPixel);
      }
    }
    finally {
      nTile.incrementAndGet();
      LOGGER.info("tile read complete {}/{}", nTile.get(), tileCount);
      t0.stop();
    }

    Slf4JStopWatch t1 = stopWatch();
    try {
      n5.writeBlock(
        pathName,
        n5.getDatasetAttributes(pathName),
        dataBlock
      );
      LOGGER.info("successfully wrote at {} to {}", gridPosition, pathName);
    }
    finally {
      t1.stop("saveBytes");
    }
  }

  /**
   * Write all resolutions for the current series to an intermediate form.
   * Readers should be initialized and have the correct series state.
   *
   * @param series the reader series index to be converted
   * @throws FormatException
   * @throws IOException
   * @throws InterruptedException
   * @throws EnumerationException
   */
  public void saveResolutions(int series)
    throws FormatException, IOException, InterruptedException,
           EnumerationException
  {
    IFormatReader workingReader = readers.take();
    int resolutions = 1;
    int sizeX;
    int sizeY;
    int imageCount;
    try {
      isLittleEndian = workingReader.isLittleEndian();
      // calculate a reasonable pyramid depth if not specified as an argument
      if (pyramidResolutions == null) {
        int width = workingReader.getSizeX();
        int height = workingReader.getSizeY();
        while (width > MIN_SIZE || height > MIN_SIZE) {
          resolutions++;
          width /= PYRAMID_SCALE;
          height /= PYRAMID_SCALE;
        }
      }
      else {
        resolutions = pyramidResolutions;
      }
      LOGGER.info("Using {} pyramid resolutions", resolutions);
      sizeX = workingReader.getSizeX();
      sizeY = workingReader.getSizeY();
      imageCount = workingReader.getImageCount();
      pixelType = workingReader.getPixelType();
    }
    finally {
      readers.put(workingReader);
    }

    LOGGER.info(
      "Preparing to write pyramid sizeX {} (tileWidth: {}) " +
      "sizeY {} (tileWidth: {}) imageCount {}",
        sizeX, tileWidth, sizeY, tileHeight, imageCount
    );

    // Prepare N5 dataset
    DataType dataType;
    switch (pixelType) {
      case FormatTools.INT8:
        dataType = DataType.INT8;
        break;
      case FormatTools.UINT8:
        dataType = DataType.UINT8;
        break;
      case FormatTools.INT16:
        dataType = DataType.INT16;
        break;
      case FormatTools.UINT16:
        dataType = DataType.UINT16;
        break;
      default:
        throw new FormatException("Unsupported pixel type: "
            + FormatTools.getPixelTypeString(pixelType));
    }

    Compression compression = N5Compression.getCompressor(compressionType,
            compressionParameter);

    final String pyramidPath = outputPath.resolve(pyramidName).toString();
    final N5Writer n5 = fileType.writer(pyramidPath);
    n5.setAttribute("/", "bioformats2raw.layout", LAYOUT);

    for (int resCounter=0; resCounter<resolutions; resCounter++) {
      final int resolution = resCounter;
      int scale = (int) Math.pow(PYRAMID_SCALE, resolution);
      int scaledWidth = sizeX / scale;
      int scaledHeight = sizeY / scale;

      int activeTileWidth = tileWidth;
      int activeTileHeight = tileHeight;
      if (scaledWidth < activeTileWidth) {
        LOGGER.warn("Reducing active tileWidth to {}", scaledWidth);
        activeTileWidth = scaledWidth;
      }

      if (scaledHeight < activeTileHeight) {
        LOGGER.warn("Reducing active tileHeight to {}", scaledHeight);
        activeTileHeight = scaledHeight;
      }

      n5.createDataset(
          "/" +  String.format(scaleFormatString, series, resolution),
          getDimensions(workingReader, scaledWidth, scaledHeight),
          new int[] {activeTileWidth, activeTileHeight, 1, 1, 1},
          dataType, compression
      );

      nTile = new AtomicInteger(0);
      tileCount = (int) Math.ceil((double) scaledWidth / tileWidth)
          * (int) Math.ceil((double) scaledHeight / tileHeight)
          * imageCount;
      List<CompletableFuture<Void>> futures =
        new ArrayList<CompletableFuture<Void>>();
      for (int j=0; j<scaledHeight; j+=tileHeight) {
        final int yy = j;
        int height = (int) Math.min(tileHeight, scaledHeight - yy);
        for (int k=0; k<scaledWidth; k+=tileWidth) {
          final int xx = k;
          int width = (int) Math.min(tileWidth, scaledWidth - xx);
          for (int i=0; i<imageCount; i++) {
            final int plane = i;

            CompletableFuture<Void> future = new CompletableFuture<Void>();
            futures.add(future);
            executor.execute(() -> {
              try {
                processTile(series, resolution, plane, xx, yy, width, height);
                LOGGER.info(
                    "Successfully processed tile; resolution={} plane={} " +
                    "xx={} yy={} width={} height={}",
                    resolution, plane, xx, yy, width, height);
                future.complete(null);
              }
              catch (Exception e) {
                future.completeExceptionally(e);
                LOGGER.error(
                  "Failure processing tile; resolution={} plane={} " +
                  "xx={} yy={} width={} height={}",
                  resolution, plane, xx, yy, width, height, e);
              }
            });
          }
        }
      }

      // Wait until the entire resolution has completed before proceeding to
      // the next one
      CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[futures.size()])).join();

      // TODO: some of these futures may be completelyExceptionally
      //  and need re-throwing

    }

  }

  /**
   * Takes exception from asynchronous execution and re-throw known exception
   * types. If the end is reached with no known exception detected, either the
   * exception itself will be thrown if {@link RuntimeException}, otherwise
   * wrap in a {@link RuntimeException}.
   *
   * @param t Exception raised during processing.
   */
  private void unwrapException(Throwable t)
          throws FormatException, IOException, InterruptedException
  {
    if (t instanceof CompletionException) {
      try {
        throw ((CompletionException) t).getCause();
      }
      catch (FormatException | IOException | InterruptedException e2) {
        throw e2;
      }
      catch (RuntimeException rt) {
        throw rt;
      }
      catch (Throwable t2) {
        throw new RuntimeException(t);
      }
    }
    else if (t instanceof RuntimeException) {
      throw (RuntimeException) t;
    }
    else {
      throw new RuntimeException(t);
    }
  }

  /**
   * Save the current series as a separate image (label/barcode, etc.).
   *
   * @param filename the relative path to the output file
   */
  public void saveExtraImage(String filename)
    throws FormatException, IOException, InterruptedException
  {
    IFormatReader reader = readers.take();
    try (ImageWriter writer = new ImageWriter()) {
      IMetadata metadata = MetadataTools.createOMEXMLMetadata();
      MetadataTools.populateMetadata(metadata, 0, null,
        reader.getCoreMetadataList().get(reader.getCoreIndex()));
      writer.setMetadataRetrieve(metadata);
      writer.setId(outputPath.resolve(filename).toString());
      writer.saveBytes(0, reader.openBytes(0));
    }
    finally {
      readers.put(reader);
    }
  }

  private OMEXMLService getService() throws FormatException {
    try {
      ServiceFactory factory = new ServiceFactory();
      return factory.getInstance(OMEXMLService.class);
    }
    catch (DependencyException de) {
      throw new MissingLibraryException(OMEXMLServiceImpl.NO_OME_XML_MSG, de);
    }
  }

  /**
   * @return an empty IMetadata object for metadata transport.
   * @throws FormatException
   */
  private IMetadata createMetadata() throws FormatException {
    try {
      return getService().createOMEXMLMetadata();
    }
    catch (ServiceException se) {
      throw new FormatException(se);
    }
  }

  private Slf4JStopWatch stopWatch() {
    return new Slf4JStopWatch(LOGGER, Slf4JStopWatch.DEBUG_LEVEL);
  }

  /**
   * Perform file conversion as specified by command line arguments.
   * @param args command line arguments
   */
  public static void main(String[] args) {
    CommandLine.call(new Converter(), args);
  }

}
