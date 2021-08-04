public class Main {

    enum OPERATION {
        READ, WRITE
    }

    public static void main(String[] args) {

        int[][] options = {
                //  operationsCount, ioSegmentLen, syncIOThreadCount, asyncIOThreadCount
                {50_000, 10_000, 10, 1}, {50_000, 10_000, 20, 1},
                {50_000, 10_000, 30, 1}, {50_000, 10_000, 40, 1},
                {50_000, 10_000, 50, 1},

                {150_000, 10_000, 10, 1}, {150_000, 10_000, 20, 1},
                {150_000, 10_000, 30, 1}, {150_000, 10_000, 40, 1},
                {150_000, 10_000, 50, 1},
        };

        for (int[] option: options) {
            WriteOperations writer = new WriteOperations(option[0], option[1], option[2], option[3]);
            ReadOperations reader = new ReadOperations(option[0], option[1], option[2], option[3]);
            long[] write = writer.invoke();
            long[] read = reader.invoke();

            interpret(OPERATION.WRITE, option, write);
            interpret(OPERATION.READ, option, read);
            System.out.println();
        }
    }


    private static void interpret(OPERATION op, int[] options, long[] res) {
        String s = String.format("%s: operationsCount - %d, ioSegmentLen - %d, syncIOThreadCount - %d, " +
                "asyncIOThreadCount - %d. Runtime in MS: \t sync: %d, async: %d",
                op, options[0], options[1], options[2], options[3], res[0], res[1]);
        System.out.println(s);
    }

}
