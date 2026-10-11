import dev.dootah.portable.PortableProgram;
import org.json.*;
public class Validate {
 public static void main(String[] args) throws Exception {
  if(args.length == 1 && args[0].equals("--self-test")) { PortableProgram.contract(); PortableProgram.previousContract(); return; }
  String raw=new String(System.in.readNBytes(131073),java.nio.charset.StandardCharsets.UTF_8);
  JSONObject input=new JSONObject(raw), contract=input.getJSONObject("contract");
  // Either negotiated document; a baseline without compose.interaction.v1 never receives a program requiring it.
  boolean interaction=PortableProgram.negotiate(contract.getJSONObject("capabilities"));
  JSONObject artifact=input.getJSONObject("artifact");
  if(!interaction && artifact.getJSONArray("requires").toList().contains(PortableProgram.INTERACTION)) throw new IllegalArgumentException("Contract");
  PortableProgram program=new PortableProgram(artifact.toString(),input.getString("runtime"));
  JSONObject functions=contract.getJSONObject("functions");
  for(String id:program.functionIds()) if(!functions.has(id)||!new JSONArray(program.parameters(id)).similar(functions.getJSONArray(id))) throw new IllegalArgumentException("Installed function ABI");
 }
}
