/* Rdiff.java -- rdiff workalike program.
   Copyright (C) 2003, 2007  Casey Marshall <rsdio@metastatic.org>

This file is a part of Jarsync.

Jarsync is free software; you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the
Free Software Foundation; either version 2 of the License, or (at your
option) any later version.

Jarsync is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
for more details.

You should have received a copy of the GNU General Public License
along with Jarsync; if not, write to the Free Software Foundation,
Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

Linking Jarsync statically or dynamically with other modules is making
a combined work based on Jarsync.  Thus, the terms and conditions of
the GNU General Public License cover the whole combination.

As a special exception, the copyright holders of Jarsync give you
permission to link Jarsync with independent modules to produce an
executable, regardless of the license terms of these independent
modules, and to copy and distribute the resulting executable under
terms of your choice, provided that you also meet, for each linked
independent module, the terms and conditions of the license of that
module.  An independent module is a module which is not derived from
or based on Jarsync.  If you modify Jarsync, you may extend this
exception to your version of it, but you are not obligated to do so.
If you do not wish to do so, delete this exception statement from your
version.

ALTERNATIVELY, Jarsync may be licensed under the Apache License,
Version 2.0 (the "License"); you may not use this file except in
compliance with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied.  See the License for the specific language governing
permissions and limitations under the License.

If you modify Jarsync, you may extend this same choice of license for
your library, but you are not obligated to do so. If you do not offer
the same license terms, delete the license terms that your library is
NOT licensed under.  */

package org.metastatic.rsync;

import gnu.getopt.Getopt;
import gnu.getopt.LongOpt;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.metastatic.HASH_ALGORITHM;

/**
 * A re-implementation of the <code>rdiff</code> utility from librsync. For more
 * info see <a href="http://rproxy.samba.org/">the rproxy page</a>.
 * 
 * @version $Revision$
 */
public class Rdiff
{

  // Constants and variables.
  // -----------------------------------------------------------------

  /** The short options. */
  protected static final String OPTSTRING = "b:I:i::pS:sO:vVz::hd";

  /** The long options. */
  protected static final LongOpt[] LONGOPTS = new LongOpt[] {
      new LongOpt("block-size", LongOpt.REQUIRED_ARGUMENT, null, 'b'),
      new LongOpt("bzip2", LongOpt.OPTIONAL_ARGUMENT, null, 'i'),
      new LongOpt("help", LongOpt.NO_ARGUMENT, null, 'h'),
      new LongOpt("input-size", LongOpt.REQUIRED_ARGUMENT, null, 'I'),
      new LongOpt("gzip", LongOpt.OPTIONAL_ARGUMENT, null, 'z'),
      new LongOpt("output-size", LongOpt.REQUIRED_ARGUMENT, null, 'O'),
      new LongOpt("paranoia", LongOpt.NO_ARGUMENT, null, 'P'),
      new LongOpt("pipe", LongOpt.NO_ARGUMENT, null, 'p'),
      new LongOpt("statistics", LongOpt.NO_ARGUMENT, null, 's'),
      new LongOpt("sum-size", LongOpt.REQUIRED_ARGUMENT, null, 'S'),
      new LongOpt("verbose", LongOpt.NO_ARGUMENT, null, 'v'),
      new LongOpt("version", LongOpt.NO_ARGUMENT, null, 'V'),
      new LongOpt("debug", LongOpt.NO_ARGUMENT, null, 'd') };

  public static final int CHUNK_SIZE = 32768;

  /** Rdiff/rproxy default block length. */
  public static final int RDIFF_BLOCK_LENGTH = 2048;

  /** Rdiff/rproxy default sum length. */
  public static final int RDIFF_STRONG_LENGTH = 8;

  /** Rdiff/rproxy signature magic. */
  public static final int SIG_MAGIC = 0x72730136;

  /** Rdiff/rproxy delta magic. */
  public static final int DELTA_MAGIC = 0x72730236;

  public static final byte OP_END = 0x00;

  public static final byte OP_LITERAL_N1 = 0x41;
  public static final byte OP_LITERAL_N2 = 0x42;
  public static final byte OP_LITERAL_N4 = 0x43;
  public static final byte OP_LITERAL_N8 = 0x44;

  public static final byte OP_COPY_N4_N4 = 0x4f;

  /** The `signature' command. */
  public static final String SIGNATURE = "signature";

  /** The `delta' command. */
  public static final String DELTA = "delta";

  /** The `patch' command. */
  public static final String PATCH = "patch";

  /** The program name printed to the console. */
  public static final String PROGNAME = "rdiff";

  public static final short CHAR_OFFSET = 31;

  /** Whether or not to trace to System.err. */
  protected static boolean verbose = false;

  protected static boolean debug = false;

  /**
   * The length of blocks to checksum.
   */
  protected int blockLength;

  /**
   * The effective strong signature length.
   */
  protected int strongSumLength;

  // Constructors.
  // -----------------------------------------------------------------

  /**
   * Create an Rdiff object.
   */
  public Rdiff() {
    blockLength = RDIFF_BLOCK_LENGTH;
    strongSumLength = RDIFF_STRONG_LENGTH;
  }

  // Main entry point.
  // -----------------------------------------------------------------

  /**
   * Main entry point for console use.
   * 
   * @param argv
   *          The argument vector.
   */
  public static void main(String[] argv) throws Throwable
  {
    Getopt g = new Getopt(PROGNAME, argv, OPTSTRING, LONGOPTS);
    int c;
    Rdiff rdiff = new Rdiff();
    boolean showStats = false;
    boolean pipe = false;

    // parse the command line
    while ((c = g.getopt()) != -1)
      {
        switch (c)
          {
          case 'b':
            try
              {
                rdiff.blockLength = Integer.parseInt(g.getOptarg());
                if (rdiff.blockLength < 1)
                  {
                    throw new NumberFormatException();
                  }
              } catch (NumberFormatException nfe)
              {
                System.err.println(PROGNAME + ": bad block size.");
                System.exit(1);
              }
            break;
          case 'h':
            usage(System.out);
            System.exit(0);
          case 'I':
            break;
          case 'i':
            break;
          case 'P':
            break;
          case 'p':
            pipe = true;
            break;
          case 'S':
            try
              {
                rdiff.strongSumLength = Integer.parseInt(g.getOptarg());
                if (rdiff.strongSumLength != HASH_ALGORITHM.DIGEST_LENGTH)
                  {
                    throw new NumberFormatException();
                  }
              } catch (NumberFormatException nfe)
              {
                System.err.println(PROGNAME
                    + ": bad sum length; must be > 0 and < "
                    + HASH_ALGORITHM.DIGEST_LENGTH + ".");
                System.exit(1);
              }
            break;
          case 's':
            showStats = true;
            break;
          case 'V':
            version(System.out);
            System.exit(0);
            break;
          case 'v':
            verbose = true;
            break;
          case 'z':
            break;
          case 'd':
            debug = true;
            System.out.printf("[RDIFF] debugging enabled%n");
            break;
          case '?':
            System.err.println("Try `" + PROGNAME + " --help' for more info.");
            System.exit(1);
          }
      }

    // Parse the command.
    String command = null;
    if (g.getOptind() < argv.length)
      {
        command = argv[g.getOptind()];
      } else
      {
        System.err.println(PROGNAME + ": you must specify an action: "
            + "`signature', `delta', or `patch'.");
        System.err.println("Try `" + PROGNAME + " --help' for more info.");
        System.exit(1);
      }

    if (verbose)
      {
        System.err.println("bs=" + rdiff.blockLength + " sl="
            + rdiff.strongSumLength);
      }

    // The command is `signature'; generate signatures for the input
    // file (or System.in) and write them to the output file (or
    // System.out).
    if (SIGNATURE.startsWith(command))
      {
        if (verbose)
          {
            System.err.println("Command is `signature'.");
          }
        OutputStream out = System.out;
        InputStream in = System.in;
        if (argv.length > g.getOptind() + 1)
          {
            try
              {
                in = new FileInputStream(argv[g.getOptind() + 1]);
                if (verbose)
                  {
                    System.err.println("Reading basis from file "
                        + argv[g.getOptind() + 1]);
                  }
              } catch (FileNotFoundException fnfe)
              {
                System.err.println(PROGNAME + ": Error opening \""
                    + argv[g.getOptind() + 1]
                    + "\" for reading: No such file or directory.");
                System.exit(1);
              }
          } else if (verbose)
          {
            System.err.println("Reading basis from standard input.");
          }
        // if (in != System.in) {
        // in.close();
        // }

        if (argv.length > g.getOptind() + 2)
          {
            try
              {
                out = new FileOutputStream(argv[g.getOptind() + 2]);
                if (verbose)
                  {
                    System.err.println("Writing signatures to file "
                        + argv[g.getOptind() + 2]);
                  }
              } catch (FileNotFoundException fnfe)
              {
                System.err.println(PROGNAME + ": Error opening \""
                    + argv[g.getOptind() + 2]
                    + "\" for reading: No such file or directory.");
                System.exit(1);
              }
          } else if (verbose)
          {
            System.err.println("Writing signatures to standard output.");
          }
        if (pipe)
          {
            rdiff.makeSignatures(in, out);
          } else
          {
            List sums = rdiff.makeSignatures(in);
            rdiff.writeSignatures(sums, out);
            if (showStats)
              {
                System.err.println(PROGNAME + ": signature statistics: "
                    + "signature[" + sums.size() + " blocks, "
                    + rdiff.blockLength + " bytes per block]");
              }
          }
        if (in != System.in)
          {
            in.close();
          }
        if (out != System.out)
          {
            out.close();
          }

        // Command is `delta'; read signatures from the given file,
        // generate deltas for the input file (or System.in) and write the
        // deltas to the output file (or System.out).
      } else if (DELTA.startsWith(command))
      {
        if (verbose)
          {
            System.err.println("Command is `delta'.");
          }
        InputStream sigsIn = null;
        InputStream newIn = System.in;
        OutputStream out = System.out;
        if (argv.length > g.getOptind() + 1)
          {
            try
              {
                sigsIn = new FileInputStream(argv[g.getOptind() + 1]);
                if (verbose)
                  {
                    System.err.println("Reading signatures from "
                        + argv[g.getOptind() + 1]);
                  }
              } catch (FileNotFoundException fnfe)
              {
                System.err.println(PROGNAME + ": Error opening \""
                    + argv[g.getOptind() + 1]
                    + "\" for reading: No such file or directory.");
                System.exit(1);
              }
          } else
          {
            System.err.println("Usage for delta: " + PROGNAME
                + " [OPTIONS] delta SIGNATURE [NEWFILE [DELTA]]");
            System.err.println("Try `" + PROGNAME
                + " --help' for more information.");
            System.exit(1);
          }
        List<ChecksumPair> sigs = rdiff.readSignatures(sigsIn);
        sigsIn.close();
        if (showStats)
          {
            System.err.println(PROGNAME + ": loadsig statistics: "
                + "signature[" + sigs.size() + " blocks, " + rdiff.blockLength
                + " bytes per block]");
          }

        if (argv.length > g.getOptind() + 2)
          {
            try
              {
                newIn = new FileInputStream(argv[g.getOptind() + 2]);
                if (verbose)
                  {
                    System.err.println("Reading new file from "
                        + argv[g.getOptind() + 2]);
                  }
              } catch (FileNotFoundException fnfe)
              {
                System.err.println(PROGNAME + ": Error opening \""
                    + argv[g.getOptind() + 2]
                    + "\" for reading: No such file or directory.");
                System.exit(1);
              }
          } else if (verbose)
          {
            System.err.println("Reading new file from standard input.");
          }
        if (argv.length > g.getOptind() + 3)
          {
            try
              {
                out = new FileOutputStream(argv[g.getOptind() + 3]);
                if (verbose)
                  {
                    System.err.println("Writing deltas to file "
                        + argv[g.getOptind() + 3]);
                  }
              } catch (FileNotFoundException fnfe)
              {
                System.err.println(PROGNAME + ": Error opening \""
                    + argv[g.getOptind() + 3]
                    + "\" for writing: No such file or directory.");
                System.exit(1);
              }
          } else if (verbose)
          {
            System.err.println("Writing deltas to standard output.");
          }

        if (pipe)
          {
            rdiff.makeDeltas(sigs, newIn, out);
          } else
          {
            List<Delta> deltas = rdiff.makeDeltas(sigs, newIn);
            if (showStats)
              {
                int lit = 0;
                long litBytes = 0;
                int litCmdBytes = 0;
                int copy = 0;
                long copyBytes = 0;
                System.err.print(PROGNAME + ": delta statistics:");
                for (Iterator i = deltas.iterator(); i.hasNext();)
                  {
                    Object o = i.next();
                    if (o instanceof Offsets)
                      {
                        copy++;
                        copyBytes += ((Offsets) o).getBlockLength();
                      } else
                      {
                        lit++;
                        litBytes += ((DataBlock) o).getBlockLength();
                        litCmdBytes += 1 + integerLength(((DataBlock) o)
                            .getBlockLength());
                      }
                  }
                if (lit > 0)
                  {
                    System.err.print(" literal[" + lit + " cmds, " + litBytes
                        + " bytes, " + litCmdBytes + " cmdbytes]");
                  }
                if (copy > 0)
                  {
                    System.err.print(" copy[" + copy + " cmds, " + copyBytes
                        + " bytes, 0 false, " + copy * 9 + " cmdbytes]");
                  }
                System.err.println();
              }
            rdiff.writeDeltas(deltas, out);
          }

        if (newIn != System.in)
          {
            newIn.close();
          }
        if (out != System.out)
          {
            out.close();
          }

        // Command is `patch'; read the given basis file, read deltas from
        // the given file (or System.in), and reconstruct the file to the
        // given location (or System.out).
      } else if (PATCH.startsWith(command))
      {
        if (verbose)
          {
            System.err.println("Command is `patch'.");
          }
        File basis = null;
        InputStream deltasIn = System.in;
        OutputStream newFile = System.out;
        if (argv.length > g.getOptind() + 1)
          {
            try
              {
                basis = new File(argv[g.getOptind() + 1]);
                if (!basis.exists())
                  {
                    throw new FileNotFoundException();
                  }
                if (verbose)
                  {
                    System.err.println("Reading basis file "
                        + argv[g.getOptind() + 1]);
                  }
              } catch (FileNotFoundException fnfe)
              {
                System.err.println(PROGNAME + ": Error opening \""
                    + argv[g.getOptind() + 1]
                    + "\" for reading: No such file or directory.");
                System.exit(1);
              }
          } else
          {
            System.err.println("Usage for patch: " + PROGNAME
                + " [OPTIONS] patch BASIS [DELTA [NEW]]");
            System.err.println("Try `" + PROGNAME
                + " --help' for more information.");
            System.exit(1);
          }

        if (argv.length > g.getOptind() + 2)
          {
            try
              {
                deltasIn = new FileInputStream(argv[g.getOptind() + 2]);
                if (verbose)
                  {
                    System.err.println("Reading deltas from file "
                        + argv[g.getOptind() + 2]);
                  }
              } catch (FileNotFoundException fnfe)
              {
                System.err.println(PROGNAME + ": Error opening \""
                    + argv[g.getOptind() + 2]
                    + "\" for reading: No such file or directory.");
                System.exit(1);
              }
          } else if (verbose)
          {
            System.err.println("Reading deltas from standard input.");
          }

        if (argv.length > g.getOptind() + 3)
          {
            try
              {
                newFile = new FileOutputStream(argv[g.getOptind() + 3]);
                if (verbose)
                  {
                    System.err.println("Writing new file "
                        + argv[g.getOptind() + 3]);
                  }
              } catch (FileNotFoundException fnfe)
              {
                System.err.println(PROGNAME + ": Error opening \""
                    + argv[g.getOptind() + 3]
                    + "\" for writing: No such file or directory.");
                System.exit(1);
              }
          } else if (verbose)
          {
            System.err.println("Writing new file to standard output.");
          }

        if (pipe)
          {
            rdiff.rebuildFile(basis, deltasIn, newFile);
          } else
          {
            List deltas = rdiff.readDeltas(deltasIn);
            if (showStats)
              {
                int lit = 0;
                long litBytes = 0;
                int litCmdBytes = 0;
                int copy = 0;
                long copyBytes = 0;
                System.err.print(PROGNAME + ": patch statistics:");
                for (Iterator i = deltas.iterator(); i.hasNext();)
                  {
                    Object o = i.next();
                    if (o instanceof Offsets)
                      {
                        copy++;
                        copyBytes += ((Offsets) o).getBlockLength();
                      } else
                      {
                        lit++;
                        litBytes += ((DataBlock) o).getBlockLength();
                        litCmdBytes += 1 + integerLength(((DataBlock) o)
                            .getBlockLength());
                      }
                  }
                if (lit > 0)
                  {
                    System.err.print(" literal[" + lit + " cmds, " + litBytes
                        + " bytes, " + litCmdBytes + " cmdbytes]");
                  }
                if (copy > 0)
                  {
                    System.err.print(" copy[" + copy + " cmds, " + copyBytes
                        + " bytes, 0 false, " + copy * 9 + " cmdbytes]");
                  }
                System.err.println();
              }
            rdiff.rebuildFile(basis, deltas, newFile);
          }

        if (deltasIn != System.in)
          {
            deltasIn.close();
          }
        if (newFile != System.out)
          {
            newFile.close();
          }

      } else
      {
        System.err.println(PROGNAME + ": you must specify an action: "
            + "`signature', `delta', or `patch'.");
        System.err.println("Try `" + PROGNAME + " --help' for more info.");
        System.exit(1);
      }

  }

  // Public instance methods.
  // -----------------------------------------------------------------

  /**
   * Generate and write the signatures.
   */
  public void makeSignatures(InputStream in, final OutputStream out)
      throws IOException, NoSuchAlgorithmException
  {
    Configuration c = new Configuration();
    c.debug = Rdiff.debug;
    c.strongSum = MessageDigest.getInstance(HASH_ALGORITHM.DIGEST_NAME);
    c.weakSum = new Checksum32(CHAR_OFFSET);
    c.blockLength = blockLength;
    c.strongSumLength = strongSumLength;
    GeneratorStream gen = new GeneratorStream(c);
    gen.addListener(new GeneratorListener() {
      public void update(GeneratorEvent ev) throws ListenerException
      {
        ChecksumPair pair = ev.getChecksumPair();
        if (Rdiff.debug)
          {
            System.out.printf("[RDIFF] generator event %s%n", pair);
          }
        try
          {
            Rdiff.writeInt(pair.getWeak(), out);
            out.write(pair.strong, 0, strongSumLength);
          } catch (IOException ioe)
          {
            throw new ListenerException(ioe);
          }
      }
    });
    writeInt(SIG_MAGIC, out);
    writeInt(blockLength, out);
    writeInt(strongSumLength, out);
    int len = 0;
    byte[] buf = new byte[CHUNK_SIZE];
    while ((len = in.read(buf)) != -1)
      {
        try
          {
            gen.update(buf, 0, len);
          } catch (ListenerException le)
          {
            throw (IOException) le.getCause();
          }
      }
    try
      {
        gen.doFinal();
      } catch (ListenerException le)
      {
        throw (IOException) le.getCause();
      }
  }

  /**
   * Write the signatures to the specified output stream.
   * 
   * @param sigs
   *          The signatures to write.
   * @param out
   *          The OutputStream to write to.
   * @throws java.io.IOException
   *           If writing fails.
   */
  public void writeSignatures(List sigs, OutputStream out) throws IOException
  {
    writeInt(SIG_MAGIC, out);
    writeInt(blockLength, out);
    writeInt(strongSumLength, out);
    for (Iterator i = sigs.iterator(); i.hasNext();)
      {
        ChecksumPair pair = (ChecksumPair) i.next();
        writeInt(pair.getWeak(), out);
        out.write(pair.getStrong(), 0, strongSumLength);
      }
  }

  /**
   * Make the signatures from data coming in through the input stream.
   * 
   * @param in
   *          The input stream to generate signatures for.
   * @return A List of signatures.
   * @throws java.io.IOException
   *           If reading fails.
   */
  public List makeSignatures(InputStream in) throws IOException,
      NoSuchAlgorithmException
  {
    Configuration c = new Configuration();
    c.strongSum = MessageDigest.getInstance(HASH_ALGORITHM.DIGEST_NAME);
    c.weakSum = new Checksum32(CHAR_OFFSET);
    c.blockLength = blockLength;
    c.strongSumLength = strongSumLength;
    return new Generator(c).generateSums(in);
  }

  /**
   * Read the signatures from the input stream.
   * 
   * @param in
   *          The InputStream to read the signatures from.
   * @return A collection of {@link ChecksumPair}s read.
   * @throws java.io.IOException
   *           If the input stream is malformed.
   */
  public List<ChecksumPair> readSignatures(InputStream in) throws IOException
  {
    List<ChecksumPair> sigs = new LinkedList<ChecksumPair>();
    int header = readInt(in);
    if (header != SIG_MAGIC)
      {
        throw new IOException("Bad signature header: 0x"
            + Integer.toHexString(header));
      }
    long off = 0;
    blockLength = readInt(in);
    strongSumLength = readInt(in);

    do
      {
        try
          {
            int weak = readInt(in);
            byte[] strong = new byte[strongSumLength];
            int len = in.read(strong);
            if (len < strongSumLength)
              break;
            sigs.add(new ChecksumPair(weak, strong, off));
            off += blockLength;
          } catch (EOFException eof)
          {
            break;
          }
      } while (true);
    if (Rdiff.debug)
      {
        System.out.printf("[RDIFF] sigs: %s%n", sigs);
      }
    return sigs;
  }

  public void makeDeltas(List<ChecksumPair> sums, InputStream in,
      final OutputStream out) throws IOException, NoSuchAlgorithmException
  {
    Configuration c = new Configuration();
    c.debug = Rdiff.debug;
    c.strongSum = MessageDigest.getInstance(HASH_ALGORITHM.DIGEST_NAME);
    c.weakSum = new Checksum32(CHAR_OFFSET);
    c.blockLength = blockLength;
    c.strongSumLength = strongSumLength;
    MatcherStream match = new MatcherStream(c);
    match.setChecksums(sums);
    writeInt(DELTA_MAGIC, out);
    match.addListener(new MatcherListener() {
      public void update(MatcherEvent me) throws ListenerException
      {
        Delta d = me.getDelta();
        if (Rdiff.debug)
          {
            System.out.printf("[RDIFF] matcher event %s%n", d);
          }
        try
          {
            if (d instanceof Offsets)
              {
                Rdiff.writeCopy((Offsets) d, out);
              } else if (d instanceof DataBlock)
              {
                Rdiff.writeLiteral((DataBlock) d, out);
              }
          } catch (IOException ioe)
          {
            throw new ListenerException(ioe);
          }
      }
    });
    int len = 0;
    byte[] buf = new byte[CHUNK_SIZE];
    while ((len = in.read(buf)) != -1)
      {
        try
          {
            match.update(buf, 0, len);
          } catch (ListenerException le)
          {
            throw (IOException) le.getCause();
          }
      }
    try
      {
        match.doFinal();
      } catch (ListenerException le)
      {
        throw (IOException) le.getCause();
      }
    out.write(0);
  }

  /**
   * Write deltas to an output stream.
   * 
   * @param deltas
   *          A collection of {@link Delta}s to write.
   * @param out
   *          The OutputStream to write to.
   * @throws java.io.IOException
   *           If writing fails.
   */
  public void writeDeltas(List deltas, OutputStream out) throws IOException
  {
    writeInt(DELTA_MAGIC, out);
    for (Iterator i = deltas.iterator(); i.hasNext();)
      {
        Object o = i.next();
        if (o instanceof Offsets)
          {
            writeCopy((Offsets) o, out);
          } else if (o instanceof DataBlock)
          {
            writeLiteral((DataBlock) o, out);
          }
      }
    out.write(0);
  }

  /**
   * Make a collection of {@link Delta}s from the given sums and InputStream.
   * 
   * @param sums
   *          A collection of {@link ChecksumPair}s generated from the "old"
   *          file.
   * @param in
   *          The InputStream for the "new" file.
   * @return A collection of {@link Delta}s that will patch the old file to the
   *         new.
   * @throws java.io.IOException
   *           If reading fails.
   */
  public List<Delta> makeDeltas(List<ChecksumPair> sums, InputStream in)
      throws IOException, NoSuchAlgorithmException
  {
    Configuration c = new Configuration();
    c.strongSum = MessageDigest.getInstance(HASH_ALGORITHM.DIGEST_NAME);
    c.weakSum = new Checksum32(CHAR_OFFSET);
    c.blockLength = blockLength;
    c.strongSumLength = strongSumLength;
    return new Matcher(c).hashSearch(sums, in);
  }

  /**
   * Read a collection of {@link Delta}s from the InputStream.
   * 
   * @param in
   *          The InputStream to read from.
   * @return A collection of {@link Delta}s read.
   * @throws java.io.IOException
   *           If the input stream is malformed.
   */
  public List<Delta> readDeltas(InputStream input) throws IOException
  {
    DataInputStream in = new DataInputStream(input);
    List<Delta> deltas = new LinkedList<Delta>();
    int header = readInt(in);
    if (debug)
      {
        System.out.printf("[RDIFF] read header %x%n", header);
      }
    if (header != DELTA_MAGIC)
      {
        throw new IOException("Bad delta header: 0x"
            + Integer.toHexString(header));
      }
    int command;
    long offset = 0;
    byte[] buf;
    while ((command = in.read()) != -1)
      {
        if (debug)
          {
            System.out.printf("[RDIFF] read command %x%n", command);
          }
        switch (command)
          {
          case OP_END:
            if (debug)
              {
                System.out.printf("[RDIFF] OP_END\n");
              }
            return deltas;

          case OP_LITERAL_N1:
            {
              buf = new byte[(int) readInt(1, in)];
              in.readFully(buf);
              DataBlock db = new DataBlock(offset, buf);
              if (debug)
                {
                  System.out.printf("[RDIFF] OP_LITERAL_N1 %s%n", db);
                }
              deltas.add(db);
              offset += buf.length;
              break;
            }

          case OP_LITERAL_N2:
            {
              buf = new byte[(int) readInt(2, in)];
              in.readFully(buf);
              DataBlock db = new DataBlock(offset, buf);
              if (debug)
                {
                  System.out.printf("[RDIFF] OP_LITERAL_N2 %s%n", db);
                }
              deltas.add(db);
              offset += buf.length;
              break;
            }

          case OP_LITERAL_N4:
            {
              buf = new byte[(int) readInt(4, in)];
              in.readFully(buf);
              DataBlock db = new DataBlock(offset, buf);
              if (debug)
                {
                  System.out.printf("[RDIFF] OP_LITERAL_N4 %s%n", db);
                }
              deltas.add(db);
              offset += buf.length;
              break;
            }

          case OP_COPY_N4_N4:
            {
              int oldOff = (int) readInt(4, in);
              int bs = (int) readInt(4, in);
              Offsets o = new Offsets(oldOff, offset, bs);
              if (debug)
                {
                  System.out.printf("[RDIFF] OP_COPY_N4_N4 %s%n", o);
                }
              deltas.add(o);
              offset += bs;
              break;
            }

          default:
            throw new IOException("Bad delta command: 0x"
                + Integer.toHexString(command));
          }
      }
    throw new IOException("Didn't recieve RS_OP_END.");
  }

  public void rebuildFile(File basis, InputStream deltas, OutputStream out)
      throws IOException
  {
    File temp = File.createTempFile(".rdiff", null);
    temp.deleteOnExit();
    final RandomAccessFile f = new RandomAccessFile(temp, "rw");
    RebuilderStream rs = new RebuilderStream();
    rs.setBasisFile(basis);
    rs.addListener(new RebuilderListener() {
      public void update(RebuilderEvent re) throws ListenerException
      {
        try
          {
            f.seek(re.getOffset());
            f.write(re.getData());
          } catch (IOException ioe)
          {
            throw new ListenerException(ioe);
          }
      }
    });

    int header = readInt(deltas);
    if (debug)
      {
        System.out.printf("[RDIFF] read header %x%n", header);
      }
    if (header != DELTA_MAGIC)
      {
        throw new IOException("Bad delta header: 0x"
            + Integer.toHexString(header));
      }

    int command;
    long offset = 0;
    byte[] buf;
    boolean end = false;
    read: while ((command = deltas.read()) != -1)
      {
        try
          {
            switch (command)
              {
              case OP_END:
                end = true;
                break read;
              case OP_LITERAL_N1:
                buf = new byte[(int) readInt(1, deltas)];
                deltas.read(buf);
                rs.update(new DataBlock(offset, buf));
                offset += buf.length;
                break;
              case OP_LITERAL_N2:
                buf = new byte[(int) readInt(2, deltas)];
                deltas.read(buf);
                rs.update(new DataBlock(offset, buf));
                offset += buf.length;
                break;
              case OP_LITERAL_N4:
                buf = new byte[(int) readInt(4, deltas)];
                deltas.read(buf);
                rs.update(new DataBlock(offset, buf));
                offset += buf.length;
                break;
              case OP_COPY_N4_N4:
                int oldOff = (int) readInt(4, deltas);
                int bs = (int) readInt(4, deltas);
                rs.update(new Offsets(oldOff, offset, bs));
                offset += bs;
                break;
              default:
                throw new IOException("Bad delta command: 0x"
                    + Integer.toHexString(command));
              }
          } catch (ListenerException le)
          {
            throw (IOException) le.getCause();
          }
      }
    if (!end)
      throw new IOException("Didn't recieve RS_OP_END.");
    f.close();
    FileInputStream fin = new FileInputStream(temp);
    buf = new byte[CHUNK_SIZE];
    int len = 0;
    while ((len = fin.read(buf)) != -1)
      out.write(buf, 0, len);
  }

  /**
   * Patch the file <code>basis</code> using <code>deltas</code>, writing the
   * patched file to <code>out</code>.
   * 
   * @param basis
   *          The basis file.
   * @param deltas
   *          The collection of {@link Delta}s to apply.
   * @param out
   *          The OutputStream to write the patched file to.
   * @throws java.io.IOException
   *           If reading/writing fails.
   */
  public void rebuildFile(File basis, List deltas, OutputStream out)
      throws IOException
  {
    File temp = Rebuilder.rebuildFile(basis, deltas);
    temp.deleteOnExit();
    FileInputStream fin = new FileInputStream(temp);
    byte[] buf = new byte[CHUNK_SIZE];
    int len = 0;
    while ((len = fin.read(buf)) != -1)
      {
        out.write(buf, 0, len);
      }
  }

  // Own methods.
  // -----------------------------------------------------------------

  /**
   * Print a console usage message to <code>out</code>.
   * 
   * @param out
   *          The PrintStream to write to.
   */
  private static void usage(PrintStream out)
  {
    out.println("Usage: rdiff [OPTIONS] signature [BASIS [SIGNATURE]]");
    out.println("             [OPTIONS] delta SIGNATURE [NEWFILE [DELTA]]");
    out.println("             [OPTIONS] patch BASIS [DELTA [NEWFILE]]");
    out.println();
    out.println("Options: * == option currently unimplemented");
    out.println("  -v, --verbose             Trace internal processing");
    out.println("  -d, --debug               Trace detailed processing");
    out.println("  -V, --version             Show program version");
    out.println("  -h, --help                Show this help message");
    out.println("  -p, --pipe                Keep less intermediate data in memory");
    out.println("  -s, --statistics          Show performance statistics");
    out.println("Delta-encoding options:");
    out.println("  -b, --block-size=BYTES    Signature block size");
    out.println("  -S, --sum-size=BYTES      Set signature strength");
    out.println("*     --paranoia            Verify all rolling checksums");
    out.println("IO options:");
    out.println("* -I, --input-size=BYTES    Input buffer size");
    out.println("* -O, --output-size=BYTES   Output buffer size");
    out.println("* -z, --gzip[=LEVEL]        gzip-compress deltas");
    out.println("* -i, --bzip2[=LEVEL]       bzip2-compress deltas");
  }

  /**
   * Print our version message to <code>out</code>.
   * 
   * @param out
   *          The PrintStream to write to.
   */
  private static void version(PrintStream out)
  {
    out.println(PROGNAME + " (Jarsync " + Version.VERSION + ")");
    out.println("Copyright (C) 2002, 2007 Casey Marshall.");
    out.println();
    out.println("Jarsync comes with NO WARRANTY, to the extent permitted by law.");
    out.println("You may redistribute copies of Jarsync under the terms of the GNU");
    out.println("General Public License.  See the file `COPYING' for details.");
  }

  /**
   * Write a "COPY" command to <code>out</code>.
   * 
   * @param off
   *          The {@link Offsets} object to write as a COPY command.
   * @param out
   *          The OutputStream to write to.
   * @throws java.io.IOException
   *           if writing fails.
   */
  private static void writeCopy(Offsets off, OutputStream out)
      throws IOException
  {
    out.write(OP_COPY_N4_N4);
    writeInt(off.getOldOffset(), 4, out);
    writeInt(off.getBlockLength(), out);
  }

  /**
   * Write a "LITERAL" command to <code>out</code>.
   * 
   * @param d
   *          The {@link DataBlock} to write as a LITERAL command.
   * @param out
   *          The OutputStream to write to.
   * @throws java.io.IOException
   *           if writing fails.
   */
  private static void writeLiteral(DataBlock d, OutputStream out)
      throws IOException
  {
    byte cmd = 0;
    int param_len;

    switch (param_len = integerLength(d.getBlockLength()))
      {
      case 1:
        cmd = OP_LITERAL_N1;
        break;
      case 2:
        cmd = OP_LITERAL_N2;
        break;
      case 4:
        cmd = OP_LITERAL_N4;
        break;
      }

    out.write(cmd);
    writeInt(d.getBlockLength(), param_len, out);
    out.write(d.getData());
  }

  /**
   * Check if a long integer needs to be represented by 1, 2, 4 or 8 bytes.
   * 
   * @param l
   *          The long to test.
   * @return The effective length, in bytes, of the argument.
   */
  private static int integerLength(long l)
  {
    if ((l & ~0xffL) == 0)
      {
        return 1;
      } else if ((l & ~0xffffL) == 0)
      {
        return 2;
      } else if ((l & ~0xffffffffL) == 0)
      {
        return 4;
      }
    return 8;
  }

  /**
   * Read a variable-length integer from the input stream. This method reads
   * <code>len</code> bytes from <code>in</code>, interpolating them as
   * composing a big-endian integer.
   * 
   * @param len
   *          The number of bytes to read.
   * @param in
   *          The InputStream to read from.
   * @return The integer.
   * @throws java.io.IOException
   *           if reading fails.
   */
  private static long readInt(int len, InputStream in) throws IOException
  {
    long i = 0;
    for (int j = len - 1; j >= 0; j--)
      {
        int k = in.read();
        if (k == -1)
          throw new EOFException();
        i |= (k & 0xff) << 8 * j;
      }
    return i;
  }

  /**
   * Read a four-byte big-endian integer from the InputStream.
   * 
   * @param in
   *          The InputStream to read from.
   * @return The integer read.
   * @throws java.io.IOException
   *           if reading fails.
   */
  private static int readInt(InputStream in) throws IOException
  {
    int i = 0;
    for (int j = 3; j >= 0; j--)
      {
        int k = in.read();
        if (k == -1)
          throw new EOFException();
        i |= (k & 0xff) << 8 * j;
      }
    return i;
  }

  /**
   * Write the lowest <code>len</code> bytes of <code>l</code> to
   * <code>out</code> in big-endian byte order.
   * 
   * @param l
   *          The integer to write.
   * @param len
   *          The number of bytes to write.
   * @param out
   *          The OutputStream to write to.
   * @throws java.io.IOException
   *           If writing fails.
   */
  private static void writeInt(long l, int len, OutputStream out)
      throws IOException
  {
    for (int i = len - 1; i >= 0; i--)
      {
        out.write((int) (l >>> i * 8) & 0xff);
      }
  }

  /**
   * Write a four-byte integer in big-endian byte order to <code>out</code>.
   * 
   * @param i
   *          The integer to write.
   * @param out
   *          The OutputStream to write to.
   * @throws java.io.IOException
   *           If writing fails.
   */
  private static void writeInt(int i, OutputStream out) throws IOException
  {
    out.write((byte) ((i >>> 24) & 0xff));
    out.write((byte) ((i >>> 16) & 0xff));
    out.write((byte) ((i >>> 8) & 0xff));
    out.write((byte) (i & 0xff));
  }
}
