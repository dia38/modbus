package org.hashmap.modbus.RtuMaster;

import com.digitalpetri.modbus.codec.ModbusRequestEncoder;
import com.digitalpetri.modbus.codec.ModbusResponseDecoder;
import com.digitalpetri.modbus.codec.ModbusRtuCodec;
import com.digitalpetri.modbus.codec.ModbusRtuPayload;
import com.digitalpetri.modbus.requests.ModbusRequest;
import static io.netty.buffer.Unpooled.*;

import com.digitalpetri.modbus.requests.WriteMultipleCoilsRequest;
import com.digitalpetri.modbus.requests.WriteMultipleRegistersRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import purejavacomm.*;
import java.io.InputStream;
import java.io.OutputStream;
import io.netty.buffer.ByteBuf;

public class ModbusRtuMaster {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ModbusRtuMasterConfig config;
    private ModbusRtuCodec modbusRtuCodec;
    private ModbusRtuPayload modbusRtuPayload;
    private OutputStream outputStream;
    private InputStream inputStream;
    private final SerialManager serialManager;

    public ModbusRtuMaster(ModbusRtuMasterConfig config) {
        serialManager = new SerialManager();
        modbusRtuCodec = new ModbusRtuCodec(new ModbusRequestEncoder(), new ModbusResponseDecoder());
        this.config = config;
    }

    public ModbusRtuMasterConfig getConfig() {
        return config;
    }

    public void sendRequest(ModbusRequest request, byte slaveId) {
        SerialPort serialPort = initSerialComm();
        if (serialPort == null) {
            logger.error("Error in initializing serial port.");
            return;
        }

        byte[]byteMsg = createMsgPayload(slaveId, request);

        outputStream = serialManager.getSerialPortOutputStream(serialPort);
        if (outputStream == null) {
            logger.error("Error in initializing Output Stream for writing data on Serial Port");
            return;
        }

        serialManager.sendRequestOutStream(outputStream, byteMsg);
        inputStream = serialManager.getSerialPortInputStream(serialPort);
        if (inputStream == null) {
            logger.error("Error in initializing Input Stream for reading data on Serial Port");
            return;
        }

        ByteBuf buf = serialManager.readResponseOnSerialPort(inputStream, config);
        if (buf ==null) {
            logger.error("Error in reading data on Serial Port");
            return;
        }

        decodeReceivedBuffer(slaveId, buf);
        printBufferMsg(buf);
        serialPort.close();
    }

    private SerialPort initSerialComm() {
        CommPortIdentifier portId = serialManager.getCommPortIdentifier(config.getPortName());
        if (portId == null) {
            logger.error("Error in getting Port Identifier for PostName : " + config.getPortName());
            return null;
        }

        serialManager.addOwnerShipOfPort(portId);
        SerialPort serialPort = serialManager.openSerialPort(portId, config.getTimeout());
        if (serialPort == null) {
            logger.error("Error in Opening Serial Port. Request Timeout");
            return null;
        }

        serialManager.setFlowControlMode(serialPort, config.getFlowControl());
        serialManager.setSerialPortParams(serialPort, config);
        return serialPort;
    }

    private static int calculateCRC(ByteBuf buffer) {
        /*
            CRC is being calculate according to the xls provided by Modbus.
            Link : http://www.simplymodbus.ca/crc.xls
         */
        int crc = 0xffff;           // initial value
        int polynomial =  0xA001;   // 1010000000000001

        for(int i=0; i < buffer.capacity() - 2; i++) {
            crc = crc ^ ((int) buffer.getByte(i) & 0xFF);
            for(int j=0; j < 8; j++) {
                boolean bit16 = ((crc & 1) == 1);
                crc = crc >> 1;
                if (bit16) {
                    crc = crc ^ polynomial;
                }
            }
        }
        crc &= 0xffff;
        int b1 = crc & 0xFF;
        crc = crc >> 8;
        crc = crc | (b1 << 8);
//        System.out.println("Final CRC : " + Integer.toHexString(crc));
        return crc;
    }

    private void printBufferMsg(ByteBuf buffer) {
        StringBuilder sb = new StringBuilder();
        for (int i=0; i < buffer.capacity(); i++) {
            byte b = buffer.getByte(i);
            sb.append(String.format("%02X ", b));
        }
        System.out.println("HEX String : " + sb.toString().replaceAll("\\s+",""));
    }

    private byte[] createMsgPayload(byte slaveId, ModbusRequest request) {
        int crcCode;
        modbusRtuPayload = new ModbusRtuPayload(slaveId, request);

        ByteBuf buffer = buffer(serialManager.getSIZE_INITBUFFER());
        int fcCode = modbusRtuPayload.getModbusPdu().getFunctionCode().getCode();
        logger.debug("Function Code for the Request : " + fcCode);
        switch (fcCode) {
            case 15:
                WriteMultipleCoilsRequest writeMultipleCoilsRequest = (WriteMultipleCoilsRequest) modbusRtuPayload.getModbusPdu();
                int noOfCoilByte = writeMultipleCoilsRequest.getValues().capacity();
                buffer.capacity(serialManager.getSIZE_SLAVEID()
                               + serialManager.getSIZE_FUNCODE()
                               + serialManager.getSIZE_FIRSTADDRESS()
                               + serialManager.getSIZE_DATACOUNT()
                               + serialManager.getSIZE_NODATABYTE()
                               + noOfCoilByte
                               + serialManager.getSIZE_CRCCODE());
                logger.debug("Buffer Capacity : " + buffer.capacity() + " for Function Code : " + fcCode);
                break;
            case 16:
                WriteMultipleRegistersRequest writeMultipleRegistersRequest = (WriteMultipleRegistersRequest) modbusRtuPayload.getModbusPdu();
                int noOfRegisterByte = writeMultipleRegistersRequest.getValues().capacity();
                buffer.capacity(serialManager.getSIZE_SLAVEID()
                               + serialManager.getSIZE_FUNCODE()
                               + serialManager.getSIZE_FIRSTADDRESS()
                               + serialManager.getSIZE_DATACOUNT()
                               + serialManager.getSIZE_NODATABYTE()
                               + noOfRegisterByte
                               + serialManager.getSIZE_CRCCODE());
                logger.debug("Buffer Capacity : " + buffer.capacity() + " for Function Code : " + fcCode);
                break;
        }

        buffer = modbusRtuCodec.encode(modbusRtuPayload, buffer);

        crcCode = calculateCRC(buffer);
        logger.debug("Calculated CRC for Payload : " + crcCode);
        modbusRtuPayload.setCrcCode(crcCode);
        buffer.writeShort(crcCode);

        printBufferMsg(buffer);
        return convertBufferToByteArray(buffer);
    }

    private byte[] convertBufferToByteArray(ByteBuf buffer) {
        byte[] byteMsg = new byte[buffer.capacity()];
        for(int i=0; i < buffer.capacity(); i++) {
            byteMsg[i] = buffer.getByte(i);
//            System.out.println("byte : " + byteMsg[i]);
        }
        return byteMsg;
    }

    private void decodeReceivedBuffer(byte slaveId, ByteBuf buf) {
        try {
            modbusRtuCodec.decode(slaveId, buf);
        } catch (Exception ex) {
            logger.error("Error in decoding the Buffer Received");
        }
    }
}
