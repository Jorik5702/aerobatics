package ch.creektiger.aerobatics;

public final class App {
    private App() {
    }

    public static void main(String[] args) {
        System.out.println(greeting());
    }

    public static String greeting() {
        return "Hello Aerobatics";
    }
}
