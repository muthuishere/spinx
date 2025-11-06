package tools.muthuishere.spinx;

public interface CloudDeployer {
    void init(String configFilename);
    void setup();
    void deploy();
    void destroy();
    default void showLogs(){

    }
}