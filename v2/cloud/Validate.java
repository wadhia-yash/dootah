import dev.dootah.portable.PortableProgram;
import org.json.*;
public class Validate {
 public static void main(String[] args) throws Exception {
  if(args.length == 1 && args[0].equals("--self-test")) { PortableProgram.contract(); return; }
  String raw=new String(System.in.readNBytes(131073),java.nio.charset.StandardCharsets.UTF_8);
  JSONObject input=new JSONObject(raw), contract=input.getJSONObject("contract");
  if(!PortableProgram.contract().similar(contract.getJSONObject("capabilities"))) throw new IllegalArgumentException("Contract");
  PortableProgram program=new PortableProgram(input.getJSONObject("artifact").toString(),input.getString("runtime"));
  JSONObject functions=contract.getJSONObject("functions");
  for(String id:program.functionIds()) if(!functions.has(id)||!new JSONArray(program.parameters(id)).similar(functions.getJSONArray(id))) throw new IllegalArgumentException("Installed function ABI");
 }
}
