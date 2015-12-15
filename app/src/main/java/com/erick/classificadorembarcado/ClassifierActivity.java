package com.erick.classificadorembarcado;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

import weka.classifiers.Classifier;
import wekatools.WekaHelper;


public class ClassifierActivity extends ActionBarActivity implements WekaHelper.WekaHelperListerner{

    /**
     * Representa dispositivo bluetooth do aparelho.
     */
    private BluetoothAdapter mBluetoothAdapter;

    /**
     * Porta de comunicação bluetooth com outros dispositivos.
     */
    private BluetoothSocket mBluetoothSocket;

    /**
     * Representa um dispositivo bluetooth remoto, permite a conexão ou a aquisição de dados do aparelho.
     */
    private BluetoothDevice mBluetoothDevice;

    /**
     * Opositalmente ao fluxo de entrada, o fluxo de saída é o meio pelo qual o soquete envia dados para um dispositivo remoto.
     */
    private OutputStream escritor;

    /**
     * Opositalmente ao fluxo de saída, o fluxo de entrada é o meio pelo qual o soquete recebe dados para um dispositivo remoto.
     */
    private InputStream leitor;

    /**
     * Responsável por informar se o dispositivo está conectado a uma Bluetooth ou não.
     */

    private boolean conectado = false;

    /**
     * Armazena o nome do Bluetooth
     */

    private static final String NOMEBLUETOOTH = "EPA07";
    private static final String MENSAGEM_ATIVACAO = "1";
    private static final String MENSAGEM_DESATIVACAO = "0";

    private TextView textViewStatus;
    private TextView textViewDado;

    public Thread workerThread;
    public byte[] readBuffer;
    public int readBufferPosition;
    volatile boolean stopWorker;

    /**
     * Variável utilizada na alteração do UI dentro do Runable.
     */
    public static Activity activity;

    private static final String TAG = "MainActivity:";
    private static final String WEKA_DIRECTORY = "GripNavigation_Weka";
    private static String modelName = "";
    private WekaHelper wekaHelper = null;
    public Globals globalInstance = Globals.getInstance();
    private ManagerWeka managerWeka;

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_classifier);

        this.managerWeka = new ManagerWeka();

        /**
         * Artimanha utilizada para fazer como que seja possivível alterar a UI dentro de uma Runable.
         * (Existem outras formas melhores)
         */
        this.activity = this;

        this.textViewStatus = (TextView) findViewById(R.id.txt_conectado);
        this.textViewStatus.setText(R.string.txt_status_desconectado);

        this.textViewDado = (TextView) findViewById(R.id.txt_dado);
        this.textViewDado.setText(R.string.txt_campo_dado_padrao);

    }

    @Override
    protected void onResume() {
        super.onResume();

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if(mBluetoothAdapter == null){
            Toast.makeText(this, R.string.txt_nao_tem_bluetooth, Toast.LENGTH_SHORT).show();
        }else{
            Toast.makeText(this, R.string.txt_tem_bluetooth, Toast.LENGTH_SHORT).show();

            /**
             * Caso o Bluetooth não esteja habilitado aparece a caixinha pedindo que o habilite.
             */

            if (!mBluetoothAdapter.isEnabled()){
                Intent habilitarBluet = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(habilitarBluet, 1);
            } else {
                //sgetPairedDevices();
                mBluetoothAdapter.startDiscovery();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        //Cria o menu, e adiciona os itens no action bar, se ele estiver visivél.
        getMenuInflater().inflate(R.menu.menu_classifier, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_connect) {
            try {
                this.conectarComBluetoohArduino();
            }catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(getApplicationContext(), R.string.txt_nao_conectou, Toast.LENGTH_LONG).show();
            }

            if (conectado) {
                Log.d("EU", getString(R.string.txt_status_conectado));
                sendMessage(MENSAGEM_ATIVACAO);
                beginListenForData();
            }
        }

        if (id == R.id.action_disconnect) {
            desconectarBluetoothDoArduino();
        }

        if (id == R.id.action_bluetooth) {
            onClickListBluetooth(MenuItemCompat.getActionView(item));
        }

        if (id == R.id.action_load_model) {
            File outPath = getWekaDirectory(ClassifierActivity.WEKA_DIRECTORY);

            ClassifierActivity.modelName = "algoritmo.model";
            File dataFile = new File(outPath, ClassifierActivity.modelName);
            Log.d(TAG, "weka model to load: " + dataFile.getAbsolutePath());
            if (wekaHelper == null) {
                wekaHelper = new WekaHelper(this);
            }
            if (dataFile.exists()) {
                wekaHelper.loadModel(dataFile.getAbsolutePath());
            } else
                Toast.makeText(this, "Model doesn't exist. ABORT!", Toast.LENGTH_SHORT).show();
        }

        return super.onOptionsItemSelected(item);
    }

    public boolean desconectarBluetoothDoArduino() {
        try {
            sendMessage(MENSAGEM_DESATIVACAO);
            this.conectado = false;
            mBluetoothSocket.close();
            this.stopWorker = true;
            Thread.currentThread().interrupt();
            this.leitor.close();
            this.textViewStatus.setText(R.string.txt_status_desconectado);
            recreate();
            return true;
        }catch (Exception e) {
            return false;
        }

    }

    public boolean conectarComBluetoohArduino() {
        buscarBluetoothArduino(NOMEBLUETOOTH);

        try {
            Log.d("EU", "Conectando com o dispositivo...");
            mBluetoothAdapter.cancelDiscovery();
            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
            mBluetoothSocket = mBluetoothDevice.createRfcommSocketToServiceRecord(uuid);
            mBluetoothSocket.connect();

            this.escritor = mBluetoothSocket.getOutputStream();
            this.leitor = mBluetoothSocket.getInputStream();

            this.conectado = true;
            this.textViewStatus.setText("Conectado a " + NOMEBLUETOOTH);

            Toast.makeText(getApplicationContext(), "Conectado a " + NOMEBLUETOOTH, Toast.LENGTH_LONG).show();
            //Connect();
            return true;
        } catch (Exception e) {
            Log.d("ERROR", "" + e.getMessage());
            Toast.makeText(getApplicationContext(), "Não foi possivel conectar", Toast.LENGTH_LONG).show();
            return false;
        }

    }

    private void buscarBluetoothArduino(String nomeBluetooth){
        boolean bluetoothEncontrado = false;

        try {
            Set<BluetoothDevice> paired = mBluetoothAdapter.getBondedDevices();
            if (paired.size() > 0) {
                for (BluetoothDevice d : paired) {
                    if (d.getName().equals(nomeBluetooth)) {
                        this.mBluetoothDevice = d;
                        bluetoothEncontrado = true;
                        break;
                    }
                }
            }

            if (!bluetoothEncontrado) {
                Toast.makeText(getApplicationContext(), R.string.txt_toast_erro_bluetooth_pareamento, Toast.LENGTH_LONG).show();
                Log.d("Erro Dispositivo", getString(R.string.txt_toast_erro_bluetooth_pareamento));
            }

        } catch (Exception e) {
            Log.d("Erro no Bluetooth", getString(R.string.txt_erro_criar_conexao) + e.getMessage());
        }

    }

    public void sendMessage(String msg){
        try {
            if (conectado) {
                escritor.write(msg.getBytes());
            }

        } catch (Exception e){
            Toast.makeText(getApplicationContext(), "Não foi possivel conectar", Toast.LENGTH_LONG).show();
            Log.d("Error while sendMessa: ", e.getMessage());
        }
    }

    public void onClickListBluetooth(View view) {

        if (!mBluetoothAdapter.isEnabled()){
            Intent habilitarBluet = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(habilitarBluet, 1);

        }   else {
            try {
                mBluetoothSocket.close();
            } catch (Exception e) { e.printStackTrace();}

            //Intent intent = new Intent(this, ChoosingGsr.class);
            //intent.putExtra("statusAccelerometer",""+FlagAccelerometer);
            // FlagAccelerometer = false;
            //startActivity(intent);
            finish();

        }
    }

    void beginListenForData()
    {
        final Handler handler = new Handler();
        final byte delimiter = 10; //This is the ASCII code for a newline character

        stopWorker = false;
        readBufferPosition = 0;
        readBuffer = new byte[1024];
        workerThread = new Thread(new Runnable()
        {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    try
                    {
                        int bytesAvailable = leitor.available();

                        if(bytesAvailable > 0)
                        {
                            byte[] packetBytes = new byte[bytesAvailable];

                            leitor.read(packetBytes);
                            for(int i=0;i<bytesAvailable;i++)
                            {
                                byte b = packetBytes[i];
                                if(b == delimiter)
                                {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    readBufferPosition = 0;

                                    handler.post(new Runnable()
                                    {
                                        public void run()
                                        {
                                            TextView textView = (TextView) ClassifierActivity.activity.findViewById(R.id.txt_dado);

                                            String array[] = data.split(",");
                                            Log.d("Valores", "" + array.length);
                                            if(array.length == 8) {

                                                textView.setText(managerWeka.classificar(array));
                                            }


                                            //Log.d("TESTE", data);
                                        }
                                    });
                                }
                                else
                                {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                           //workerThread.sleep(400);
                    }
                    catch (IOException ex)
                    {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }

    private File getWekaDirectory(String dirName) {
        // Get the directory for the public documents directory.
        File file = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), dirName);

        if (!file.mkdirs()) {
            Log.e(TAG, "O diretorio Directory already exists - not created");
        }
        return file;
    }

    @Override
    public void onWekaModelLoaded(Classifier model) {
        if (model == null) {
            Log.d(TAG, "ruhroh");
            Toast.makeText(this, "Erro enquanto tentava carregar o modelo. ", Toast.LENGTH_SHORT).show();
        } else {
            globalInstance.setActiveModel(model);
            Log.d(TAG, "retreived model: " + model.toString());
            Toast.makeText(this, "Model loaded", Toast.LENGTH_SHORT).show();
        }
    }

}

