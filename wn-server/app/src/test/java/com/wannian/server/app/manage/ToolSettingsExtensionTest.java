package com.wannian.server.app.manage;

import static org.junit.jupiter.api.Assertions.*;

import com.wannian.server.app.tool.ToolRegistrationExtension;
import com.wannian.server.app.tool.LocalHostCapabilityDetector;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.InMemoryTurnMemoryPending;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.tool.*;
import com.wannian.server.kernel.tool.builtin.LoadSkillToolAdapter;
import com.wannian.server.kernel.tool.builtin.SearchMemoryToolAdapter;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolSettingsExtensionTest {
    @Test void olderConfigMigratesDefaultReadOnlyExtensionsAcrossFacetsButKeepsWritesOff() throws Exception {
        Path dir=java.nio.file.Files.createTempDirectory("tool-ext-legacy-");
        Path config=dir.resolve("wannian.json");
        java.nio.file.Files.writeString(config,"""
                {"tools":{"byName":{"calculate":"on"},"yanhuo":{"chat":["list_tools"],"work":["list_tools"],"research":["list_tools"]}}}
                """);
        var extension=new ToolRegistrationExtension() {
            @Override public Collection<ToolRegistration> registrations(){return List.of(reg("list_personas"),reg("preview_persona"),reg("switch_persona"));}
            @Override public Set<String> defaultVisibleReadOnlyNames(){return Set.of("list_personas","preview_persona");}
            @Override public Set<String> explicitIntentWriteNames(){return Set.of("switch_persona");}
            @Override public Set<String> defaultEnabledExplicitIntentWriteNames(){return Set.of("switch_persona");}
        };
        ToolSettings settings=new ToolSettings(dir.toString(),new ToolCatalog(),new ToolBindingTable(),"test",LocalHostCapabilityDetector.detect(),SearchMemoryToolAdapter.unavailable(),LoadSkillToolAdapter.unavailable(),List.of(extension));
        var migrated=settings.snapshot();
        assertEquals("on",migrated.byName().get("list_personas"));
        assertEquals("on",migrated.byName().get("preview_persona"));
        assertEquals("on",migrated.byName().get("switch_persona"));
        for(List<String> facet:List.of(migrated.yanhuo().chat(),migrated.yanhuo().work(),migrated.yanhuo().research())) {
            assertTrue(facet.contains("list_personas"));
            assertTrue(facet.contains("preview_persona"));
            assertTrue(facet.contains("switch_persona"));
        }
        String persisted=java.nio.file.Files.readString(config);
        assertTrue(persisted.contains("\"list_personas\" : \"on\""));
        assertTrue(persisted.contains("\"switch_persona\" : \"on\""));
    }

    @Test void extensionWritesDefaultOffRequireExplicitFacetAndCurrentTurnIntent() throws Exception {
        Path dir=java.nio.file.Files.createTempDirectory("tool-ext-");
        AtomicInteger calls=new AtomicInteger();
        var extension=new ToolRegistrationExtension() {
            @Override public Collection<ToolRegistration> registrations(){return List.of(reg("persona_list"),reg("switch_persona",calls));}
            @Override public Set<String> defaultVisibleReadOnlyNames(){return Set.of("persona_list");}
            @Override public Set<String> explicitIntentWriteNames(){return Set.of("switch_persona");}
            @Override public Set<String> defaultEnabledExplicitIntentWriteNames(){return Set.of("switch_persona");}
        };
        ToolCatalog catalog=new ToolCatalog();ToolBindingTable bindings=new ToolBindingTable();
        ToolSettings settings=new ToolSettings(dir.toString(),catalog,bindings,"test",LocalHostCapabilityDetector.detect(),SearchMemoryToolAdapter.unavailable(),LoadSkillToolAdapter.unavailable(),List.of(extension));
        assertEquals("on",settings.snapshot().byName().get("persona_list"));
        assertEquals("on",settings.snapshot().byName().get("switch_persona"));
        assertTrue(settings.snapshot().yanhuo().chat().contains("switch_persona"));
        Map<String,String> names=new LinkedHashMap<>(settings.snapshot().byName());names.put("switch_persona","on");
        var current=settings.snapshot().yanhuo();
        settings.update(names,new ToolSettings.FacetLists(append(current.chat(),"switch_persona"),current.work(),current.research()));
        var runtime=new DefaultToolRuntime(catalog);
        var pending=new InMemoryTurnMemoryPending(Map.of(),CompanionIdentity.YANHUO,"请把会话切换到新角色");
        var invocation=new ToolInvocation("switch-1","switch_persona","{}");
        Map<String,String> offNames=new LinkedHashMap<>(names);offNames.put("switch_persona","off");
        var offFacet=new ToolSettings.FacetLists(remove(current.chat(),"switch_persona"),remove(current.work(),"switch_persona"),remove(current.research(),"switch_persona"));
        settings.update(offNames,offFacet);
        var invisible=runtime.execute(invocation,context("off",List.of(),pending));
        assertEquals(ErrorCodes.TOOLS_NOT_ENABLED,((ToolExecutionOutcome.Rejected)invisible).code());
        assertEquals(0,calls.get());
        settings.update(names,offFacet);
        var facetHidden=runtime.execute(invocation,context("facet-hidden",List.of(),pending));
        assertEquals(ErrorCodes.TOOLS_NOT_ENABLED,((ToolExecutionOutcome.Rejected)facetHidden).code());
        assertEquals(0,calls.get());
        settings.update(names,new ToolSettings.FacetLists(append(current.chat(),"switch_persona"),current.work(),current.research()));
        var visibleDescriptor=new ToolDescriptor("switch_persona","test","{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
        var noIntent=runtime.execute(invocation,context("no-intent",List.of(visibleDescriptor),new InMemoryTurnMemoryPending(Map.of(),CompanionIdentity.YANHUO,"看看这段小说内容")));
        assertInstanceOf(ToolExecutionOutcome.Failed.class,noIntent);
        assertEquals(0,calls.get());
        var active=runtime.execute(invocation,context("visible",List.of(visibleDescriptor),pending));
        assertInstanceOf(ToolExecutionOutcome.Succeeded.class,active);
        assertEquals(1,calls.get());
    }
    private static ToolExecutionContext context(String op,List<ToolDescriptor> visible,InMemoryTurnMemoryPending pending){return ToolExecutionContext.basic(op,"turn-1","attempt-1",visible,pending);}
    private static ToolRegistration reg(String name){return reg(name,new AtomicInteger());}
    private static ToolRegistration reg(String name,AtomicInteger calls){return new ToolRegistration(name,"test",new ToolParameterSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),Set.of(),request->{calls.incrementAndGet();return new ToolAdapterResult.Succeeded("{}");},false);}
    private static List<String> append(List<String> names,String name){var out=new ArrayList<>(names);out.add(name);return List.copyOf(out);}
    private static List<String> remove(List<String> names,String name){return names.stream().filter(n->!name.equals(n)).toList();}
}
